package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Two-pass CSV parser for JTL files.
 *
 * <p><b>Pass 1</b> — collect all labels and the minimum timestamp so that
 * sub-result detection and time-offset filtering are accurate.</p>
 * <p><b>Pass 2</b> — aggregate filtered samples into
 * {@link SamplingStatCalculator} instances and 30-second time buckets.</p>
 */
public class JTLParser {

    private static final Logger log = LoggerFactory.getLogger(JTLParser.class);

    private static final String TOTAL_LABEL = "TOTAL";
    private static final long BUCKET_SIZE_MS = 30_000L;

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses a JTL file and returns aggregated results with time metadata.
     *
     * @param filePath path to the JTL CSV file; must not be null
     * @param options  filter and display options (mutated: minTimestamp is set); must not be null
     * @return {@link ParseResult} containing per-label stats, time range, and time buckets
     * @throws IOException              if the file cannot be read or is empty
     * @throws IllegalArgumentException if filePath or options is null
     */
    public ParseResult parse(String filePath, FilterOptions options) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(options, "options must not be null");

        log.info("parse: starting. filePath={}", filePath);

        // ── Pass 1: discover labels and minimum timestamp ────────
        Set<String> allLabels = new HashSet<>();
        long minTimestamp = Long.MAX_VALUE;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("JTL file is empty: " + filePath);
            }
            Map<String, Integer> colMap = buildColumnMap(headerLine.split(","));
            Integer tsIdx = colMap.get("timeStamp");
            Integer labelIdx = colMap.get("label");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = splitCsvLine(line);
                if (labelIdx != null && labelIdx < values.length) {
                    allLabels.add(values[labelIdx].trim());
                }
                if (tsIdx != null && tsIdx < values.length) {
                    try {
                        long ts = Long.parseLong(values[tsIdx].trim());
                        if (ts > 0 && ts < minTimestamp) {
                            minTimestamp = ts;
                        }
                    } catch (NumberFormatException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("parse: non-numeric timeStamp value skipped. value={}", values[tsIdx]);
                        }
                    }
                }
            }
        }
        options.minTimestamp = (minTimestamp == Long.MAX_VALUE) ? 0 : minTimestamp;

        Set<String> subResultLabels = buildSubResultLabels(allLabels);

        // ── Pass 2: aggregate ────────────────────────────────────
        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        SamplingStatCalculator totalCalc = new SamplingStatCalculator(TOTAL_LABEL);
        long testStartMs = Long.MAX_VALUE;
        long testEndMs = Long.MIN_VALUE;

        TreeMap<Long, long[]> bucketMap = new TreeMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("JTL file is empty: " + filePath);
            }
            Map<String, Integer> colMap = buildColumnMap(headerLine.split(","));

            String line;
            while ((line = reader.readLine()) != null) {
                SampleResult sr = parseLine(line, colMap);
                if (sr == null
                        || subResultLabels.contains(sr.getSampleLabel())
                        || !shouldInclude(sr, options)) {
                    continue;
                }

                String label = sr.getSampleLabel();
                results.computeIfAbsent(label, SamplingStatCalculator::new).addSample(sr);
                totalCalc.addSample(sr);

                long sampleStart = sr.getTimeStamp();
                long sampleEnd = sampleStart + sr.getTime();
                if (sampleStart < testStartMs) testStartMs = sampleStart;
                if (sampleEnd > testEndMs) testEndMs = sampleEnd;

                long bucketKey = (sampleStart / BUCKET_SIZE_MS) * BUCKET_SIZE_MS;
                long[] acc = bucketMap.computeIfAbsent(bucketKey, k -> new long[4]);
                acc[0] += sr.getTime();
                acc[1] += 1;
                acc[2] += sr.isSuccessful() ? 0 : 1;
                acc[3] += sr.getBytesAsLong();
            }
        }

        if (!results.isEmpty()) {
            results.put(TOTAL_LABEL, totalCalc);
        }

        if (testStartMs == Long.MAX_VALUE) testStartMs = 0;
        if (testEndMs == Long.MIN_VALUE) testEndMs = 0;

        List<TimeBucket> timeBuckets = buildTimeBuckets(bucketMap);
        log.info("parse: completed. labels={}, samples={}, buckets={}",
                results.size(), totalCalc.getCount(), timeBuckets.size());
        return new ParseResult(results, testStartMs, testEndMs, timeBuckets);
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the set of labels that are sub-results of a parent label.
     * A label "Foo-3" is a sub-result when both "Foo" and "Foo-3" exist in
     * {@code allLabels} and "3" is a non-empty all-digit suffix.
     */
    private Set<String> buildSubResultLabels(Set<String> allLabels) {
        Set<String> subResults = new HashSet<>();
        for (String label : allLabels) {
            int lastDash = label.lastIndexOf('-');
            if (lastDash > 0 && lastDash < label.length() - 1) {
                String suffix = label.substring(lastDash + 1);
                String parent = label.substring(0, lastDash);
                if (isPositiveInteger(suffix) && allLabels.contains(parent)) {
                    subResults.add(label);
                }
            }
        }
        return subResults;
    }

    /**
     * Converts bucket accumulators into an ordered {@link TimeBucket} list.
     */
    private List<TimeBucket> buildTimeBuckets(TreeMap<Long, long[]> bucketMap) {
        List<TimeBucket> list = new ArrayList<>(bucketMap.size());
        double bucketSec = BUCKET_SIZE_MS / 1000.0;
        for (Map.Entry<Long, long[]> e : bucketMap.entrySet()) {
            long[] acc = e.getValue();
            long count = acc[1];
            double avgRt = count > 0 ? (double) acc[0] / count : 0.0;
            double errPct = count > 0 ? (double) acc[2] / count * 100.0 : 0.0;
            double tps = count / bucketSec;
            double kbps = (double) acc[3] / bucketSec / 1024.0;
            list.add(new TimeBucket(e.getKey(), avgRt, errPct, tps, kbps));
        }
        return list;
    }

    private Map<String, Integer> buildColumnMap(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    /**
     * Parses one CSV line into a {@link SampleResult}.
     * Returns {@code null} for blank lines or lines that cannot be parsed.
     */
    private SampleResult parseLine(String line, Map<String, Integer> colMap) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            String[] v = splitCsvLine(line);
            SampleResult sr = new SampleResult();
            sr.setTimeStamp(getLong(v, colMap, "timeStamp", 0));
            long elapsed = getLong(v, colMap, "elapsed", 0);
            sr.setStampAndTime(sr.getTimeStamp(), elapsed);
            sr.setSampleLabel(getString(v, colMap, "label", "unknown"));
            sr.setResponseCode(getString(v, colMap, "responseCode", ""));
            sr.setResponseMessage(getString(v, colMap, "responseMessage", ""));
            sr.setThreadName(getString(v, colMap, "threadName", ""));
            sr.setDataType(getString(v, colMap, "dataType", ""));
            sr.setSuccessful("true".equalsIgnoreCase(getString(v, colMap, "success", "true")));
            sr.setBytes(getLong(v, colMap, "bytes", 0));
            sr.setSentBytes(getLong(v, colMap, "sentBytes", 0));
            sr.setLatency(getLong(v, colMap, "Latency", 0));
            sr.setIdleTime(getLong(v, colMap, "IdleTime", 0));
            sr.setConnectTime(getLong(v, colMap, "Connect", 0));
            return sr;
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("parseLine: malformed CSV line skipped. reason={}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Splits a CSV line while respecting double-quoted fields that may contain commas.
     */
    private String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private boolean shouldInclude(SampleResult sr, FilterOptions options) {
        String label = sr.getSampleLabel();

        if (!options.includeLabels.isBlank()) {
            boolean found = options.regExp
                    ? label.matches(options.includeLabels)
                    : label.contains(options.includeLabels);
            if (!found) return false;
        }
        if (!options.excludeLabels.isBlank()) {
            boolean excluded = options.regExp
                    ? label.matches(options.excludeLabels)
                    : label.contains(options.excludeLabels);
            if (excluded) return false;
        }
        if (options.startOffset > 0 || options.endOffset > 0) {
            long relativeSec = (sr.getTimeStamp() - options.minTimestamp) / 1000L;
            if (options.startOffset > 0 && relativeSec < options.startOffset) return false;
            if (options.endOffset > 0 && relativeSec > options.endOffset) return false;
        }
        return true;
    }

    // ── Field extraction helpers ──────────────────────────────────

    private String getString(String[] values, Map<String, Integer> map,
                             String column, String defaultValue) {
        Integer index = map.get(column);
        if (index == null || index >= values.length) return defaultValue;
        String value = values[index].trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private long getLong(String[] values, Map<String, Integer> map,
                         String column, long defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns {@code true} only when {@code str} is non-null, non-empty,
     * and consists entirely of decimal digits.
     */
    private boolean isPositiveInteger(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Public data classes
    // ─────────────────────────────────────────────────────────────

    /**
     * Aggregated parse output: stats map, wall-clock range, and time-series buckets.
     */
    public static class ParseResult {
        /**
         * Per-label aggregated statistics.
         */
        public final Map<String, SamplingStatCalculator> results;
        /**
         * Epoch millis of the first sample timestamp.
         */
        public final long startTimeMs;
        /**
         * Epoch millis of the last sample end (timestamp + elapsed).
         */
        public final long endTimeMs;
        /**
         * Total test duration in milliseconds.
         */
        public final long durationMs;
        /**
         * Ordered list of 30-second time buckets.
         */
        public final List<TimeBucket> timeBuckets;

        /**
         * Constructs a parse result.
         *
         * @param results     per-label aggregated statistics
         * @param startTimeMs epoch millis of first sample
         * @param endTimeMs   epoch millis of last sample end
         * @param timeBuckets ordered list of 30-second time buckets
         */
        public ParseResult(Map<String, SamplingStatCalculator> results,
                           long startTimeMs, long endTimeMs,
                           List<TimeBucket> timeBuckets) {
            this.results = results;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.durationMs = Math.max(0, endTimeMs - startTimeMs);
            this.timeBuckets = timeBuckets != null ? timeBuckets : Collections.emptyList();
        }
    }

    /**
     * Aggregated metrics for a single 30-second time bucket.
     */
    public static class TimeBucket {
        /**
         * Epoch millis representing the start of the bucket.
         */
        public final long epochMs;
        /**
         * Average response time in milliseconds for this bucket.
         */
        public final double avgResponseMs;
        /**
         * Percentage of failed requests in this bucket.
         */
        public final double errorPct;
        /**
         * Transactions per second in this bucket.
         */
        public final double tps;
        /**
         * Kilobytes per second received in this bucket.
         */
        public final double kbps;

        /**
         * Constructs a time bucket.
         *
         * @param epochMs       epoch millis representing the start of the bucket
         * @param avgResponseMs average response time in milliseconds
         * @param errorPct      percentage of failed requests
         * @param tps           transactions per second
         * @param kbps          kilobytes per second received
         */
        public TimeBucket(long epochMs, double avgResponseMs,
                          double errorPct, double tps, double kbps) {
            this.epochMs = epochMs;
            this.avgResponseMs = avgResponseMs;
            this.errorPct = errorPct;
            this.tps = tps;
            this.kbps = kbps;
        }
    }

    /**
     * Filter and display options passed to {@link #parse}.
     */
    public static class FilterOptions {
        /**
         * Label substring or regex to include; blank means include all.
         */
        public String includeLabels = "";
        /**
         * Label substring or regex to exclude; blank means exclude none.
         */
        public String excludeLabels = "";
        /**
         * If {@code true}, treat include/exclude patterns as regular expressions.
         */
        public boolean regExp = false;
        /**
         * Seconds from test start to begin including samples.
         */
        public int startOffset = 0;
        /**
         * Seconds from test start to stop including samples (0 = no limit).
         */
        public int endOffset = 0;
        /**
         * Percentile to calculate (1–99).
         */
        public int percentile = 90;
        /**
         * Set internally during parse — tracks the test start timestamp.
         */
        public long minTimestamp = 0;
    }
}
