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
 *
 * <p>Low-level parsing helpers are delegated to {@link JtlParserCore}.</p>
 */
public class JTLParser {

    private static final Logger log = LoggerFactory.getLogger(JTLParser.class);

    private static final String TOTAL_LABEL  = "TOTAL";
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
        Objects.requireNonNull(options,  "options must not be null");

        log.info("parse: starting. filePath={}", filePath);

        // ── Pass 1: discover labels, minimum timestamp, and sub-result labels ────
        //
        // Sub-result detection uses the consecutive-row algorithm that mirrors how
        // JMeter writes Transaction Controller results to the JTL:
        //
        //   When "Generate Parent Sample" is ON, JMeter writes the Transaction
        //   Controller row (dataType = "") immediately before its child HTTP sample
        //   (dataType = "text") and gives both the identical timeStamp and elapsed.
        //
        //   A row whose label is preceded in the file by such a controller row is
        //   a sub-result and should be excluded when generateParentSample = true.
        //
        // This is distinct from the old numeric-suffix heuristic ("Foo-1", "Foo-2"),
        // which does not apply to Transaction Controller parent-child pairs.
        Set<String> allLabels        = new HashSet<>();
        Set<String> subResultLabels  = new HashSet<>();
        long        minTimestamp     = Long.MAX_VALUE;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("JTL file is empty: " + filePath);

            Map<String, Integer> colMap     = JtlParserCore.buildColumnMap(headerLine.split(","));
            Integer tsIdx       = colMap.get("timeStamp");
            Integer labelIdx    = colMap.get("label");
            Integer elapsedIdx  = colMap.get("elapsed");
            Integer dataTypeIdx = colMap.get("dataType");

            // Previous-row fields for consecutive-row detection
            String prevTs = null, prevElapsed = null, prevDataType = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = JtlParserCore.splitCsvLine(line);

                String label    = (labelIdx    != null && labelIdx    < values.length) ? values[labelIdx].trim()    : "";
                String ts       = (tsIdx       != null && tsIdx       < values.length) ? values[tsIdx].trim()       : "";
                String elapsed  = (elapsedIdx  != null && elapsedIdx  < values.length) ? values[elapsedIdx].trim()  : "";
                String dataType = (dataTypeIdx != null && dataTypeIdx < values.length) ? values[dataTypeIdx].trim() : "";

                // Detect sub-result: prev row is a Transaction Controller (empty
                // dataType), this row is its child (non-empty dataType), both share
                // the same elapsed, and their timestamps are within 1 ms of each
                // other.  JMeter's Transaction Controller writes its parent row
                // immediately before the child with matching elapsed; the timestamps
                // are nominally identical but can differ by ±1 ms due to
                // sub-millisecond scheduling jitter on busy systems.
                if (options.generateParentSample
                        && prevDataType != null
                        && prevDataType.isEmpty()
                        && !dataType.isEmpty()
                        && !ts.isEmpty()
                        && !prevTs.isEmpty()
                        && elapsed.equals(prevElapsed)) {
                    try {
                        if (Math.abs(Long.parseLong(ts) - Long.parseLong(prevTs)) <= 1) {
                            subResultLabels.add(label);
                        }
                    } catch (NumberFormatException ignored) { /* skip malformed ts */ }
                }

                if (!label.isEmpty()) allLabels.add(label);

                if (!ts.isEmpty()) {
                    try {
                        long tsLong = Long.parseLong(ts);
                        if (tsLong > 0 && tsLong < minTimestamp) minTimestamp = tsLong;
                    } catch (NumberFormatException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("parse: non-numeric timeStamp skipped. value={}", ts);
                        }
                    }
                }

                prevTs       = ts;
                prevElapsed  = elapsed;
                prevDataType = dataType;
            }
        }
        options.minTimestamp = (minTimestamp == Long.MAX_VALUE) ? 0 : minTimestamp;

        // Numeric-suffix sub-result detection: "Foo-1", "Foo-2" are sub-results when
        // their parent label "Foo" also appears in the JTL (e.g. JMeter HTTP samplers
        // inside a Transaction Controller with Generate Parent Sample OFF, or any
        // sampler family that writes numbered child rows alongside a root row).
        for (String candidate : allLabels) {
            int dashIdx = candidate.lastIndexOf('-');
            if (dashIdx > 0 && dashIdx < candidate.length() - 1) {
                String suffix = candidate.substring(dashIdx + 1);
                String parent = candidate.substring(0, dashIdx);
                if (!suffix.isEmpty()
                        && suffix.chars().allMatch(Character::isDigit)
                        && allLabels.contains(parent)) {
                    subResultLabels.add(candidate);
                }
            }
        }

        // ── Pass 2: aggregate ────────────────────────────────────
        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        SamplingStatCalculator totalCalc = new SamplingStatCalculator(TOTAL_LABEL);
        long testStartMs = Long.MAX_VALUE;
        long testEndMs   = Long.MIN_VALUE;
        TreeMap<Long, long[]> bucketMap = new TreeMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IOException("JTL file is empty: " + filePath);

            Map<String, Integer> colMap = JtlParserCore.buildColumnMap(headerLine.split(","));
            String line;

            while ((line = reader.readLine()) != null) {
                SampleResult sr = JtlParserCore.parseLine(line, colMap);
                if (sr == null
                        || subResultLabels.contains(sr.getSampleLabel())
                        || !JtlParserCore.shouldInclude(sr, options)) {
                    continue;
                }

                // Compute elapsed; optionally subtract IdleTime (timers / pre-post
                // processors) before calling setStampAndTime — which must be called
                // exactly once on a SampleResult.
                long rawElapsed = JtlParserCore.parseElapsed(line, colMap);
                long adjusted   = (!options.includeTimerDuration && sr.getIdleTime() > 0)
                        ? Math.max(0L, rawElapsed - sr.getIdleTime())
                        : rawElapsed;
                sr.setStampAndTime(sr.getTimeStamp(), adjusted);

                String label = sr.getSampleLabel();
                results.computeIfAbsent(label, SamplingStatCalculator::new).addSample(sr);
                totalCalc.addSample(sr);

                long sampleStart = sr.getTimeStamp();
                long sampleEnd   = sampleStart + sr.getTime();
                if (sampleStart < testStartMs) testStartMs = sampleStart;
                if (sampleEnd   > testEndMs)   testEndMs   = sampleEnd;

                long bucketKey = (sampleStart / BUCKET_SIZE_MS) * BUCKET_SIZE_MS;
                long[] acc = bucketMap.computeIfAbsent(bucketKey, k -> new long[4]);
                acc[0] += sr.getTime();
                acc[1] += 1;
                acc[2] += sr.isSuccessful() ? 0 : 1;
                acc[3] += sr.getBytesAsLong();
            }
        }

        if (!results.isEmpty()) results.put(TOTAL_LABEL, totalCalc);
        if (testStartMs == Long.MAX_VALUE) testStartMs = 0;
        if (testEndMs   == Long.MIN_VALUE) testEndMs   = 0;

        List<TimeBucket> timeBuckets = JtlParserCore.buildTimeBuckets(bucketMap, BUCKET_SIZE_MS);
        log.info("parse: completed. labels={}, samples={}, buckets={}",
                results.size(), totalCalc.getCount(), timeBuckets.size());
        return new ParseResult(results, testStartMs, testEndMs, timeBuckets);
    }

    // ─────────────────────────────────────────────────────────────
    // Public data classes
    // ─────────────────────────────────────────────────────────────

    /**
     * Aggregated parse output returned by {@link #parse}.
     */
    public static class ParseResult {
        /** Per-label aggregated statistics. */
        public final Map<String, SamplingStatCalculator> results;
        /** Epoch millis of the first sample timestamp. */
        public final long startTimeMs;
        /** Epoch millis of the last sample end (timestamp + elapsed). */
        public final long endTimeMs;
        /** Total test duration in milliseconds. */
        public final long durationMs;
        /** Ordered list of 30-second time buckets. */
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
            this.results     = results;
            this.startTimeMs = startTimeMs;
            this.endTimeMs   = endTimeMs;
            this.durationMs  = Math.max(0, endTimeMs - startTimeMs);
            this.timeBuckets = timeBuckets != null ? timeBuckets : Collections.emptyList();
        }
    }

    /**
     * Aggregated metrics for a single 30-second time bucket.
     */
    public static class TimeBucket {
        /** Epoch millis representing the start of the bucket. */
        public final long   epochMs;
        /** Average response time in milliseconds for this bucket. */
        public final double avgResponseMs;
        /** Percentage of failed requests in this bucket. */
        public final double errorPct;
        /** Transactions per second in this bucket. */
        public final double tps;
        /** Kilobytes per second received in this bucket. */
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
            this.epochMs       = epochMs;
            this.avgResponseMs = avgResponseMs;
            this.errorPct      = errorPct;
            this.tps           = tps;
            this.kbps          = kbps;
        }
    }

    /**
     * Filter and display options passed to {@link #parse}.
     */
    public static class FilterOptions {
        /** Label substring or regex to include; blank means include all. */
        public String  includeLabels = "";
        /** Label substring or regex to exclude; blank means exclude none. */
        public String  excludeLabels = "";
        /** If {@code true}, treat include/exclude patterns as regular expressions. */
        public boolean regExp        = false;
        /** Seconds from test start to begin including samples. */
        public int     startOffset   = 0;
        /** Seconds from test start to stop including samples (0 = no limit). */
        public int     endOffset     = 0;
        /** Percentile to calculate (1–99). */
        public int     percentile    = 90;
        /** Set internally during parse — tracks the test start timestamp. */
        public long    minTimestamp  = 0;

        /**
         * Mirrors JMeter's Transaction Controller "Generate Parent Sample" checkbox.
         *
         * <p>{@code true} (default / ON) — sub-results are detected and excluded so
         * only parent/controller-level rows appear in the table, matching standard
         * JMeter Aggregate Report behaviour.</p>
         *
         * <p>{@code false} (OFF) — sub-result detection is skipped; every label that
         * appears in the JTL is aggregated as its own row.</p>
         */
        public boolean generateParentSample = true;

        /**
         * Mirrors JMeter's Aggregate Report "Include duration of timer and
         * pre-post processors in generated sample" checkbox.
         *
         * <p>{@code true} (default / ON) — the raw {@code elapsed} value from the JTL
         * is used as-is, matching standard JMeter Aggregate Report behaviour.</p>
         *
         * <p>{@code false} (OFF) — the {@code IdleTime} column value (time spent
         * waiting in timers and pre/post processors) is subtracted from each sample's
         * elapsed time before aggregation, yielding net processing-only response times.</p>
         */
        public boolean includeTimerDuration = true;
    }
}