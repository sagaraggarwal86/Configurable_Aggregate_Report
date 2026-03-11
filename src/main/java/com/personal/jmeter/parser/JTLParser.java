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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
            if (headerLine == null) throw new JtlParseException("JTL file is empty: " + filePath);

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
        // Resolve the chart bucket size: user setting > default 30s
        final long bucketSizeMs = (options.chartIntervalSeconds > 0)
                ? options.chartIntervalSeconds * 1_000L
                : BUCKET_SIZE_MS;

        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        SamplingStatCalculator totalCalc = new SamplingStatCalculator(TOTAL_LABEL);
        long testStartMs = Long.MAX_VALUE;
        long testEndMs   = Long.MIN_VALUE;
        TreeMap<Long, long[]> bucketMap = new TreeMap<>();
        // Error-type accumulator: "responseCode | responseMessage" → count
        Map<String, Long> errorTypeCount = new LinkedHashMap<>();
        // Latency / Connect accumulators for Advanced Web Diagnostics
        long totalLatencyMs    = 0L;
        long totalConnectMs    = 0L;
        int  latencySampleCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new JtlParseException("JTL file is empty: " + filePath);

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

                // Accumulate Latency and Connect for Advanced Web Diagnostics averages.
                // latencySampleCount counts only non-zero Latency rows: a row with
                // Latency=0 means the column is absent or was never populated, which
                // is distinct from true zero-latency (HTTP keep-alive, Connect=0 only).
                totalLatencyMs += sr.getLatency();
                totalConnectMs += sr.getConnectTime();
                if (sr.getLatency() > 0) latencySampleCount++;

                String label = sr.getSampleLabel();
                results.computeIfAbsent(label, SamplingStatCalculator::new).addSample(sr);
                totalCalc.addSample(sr);

                // Accumulate failure type for errorTypeSummary
                if (!sr.isSuccessful()) {
                    String key = (sr.getResponseCode() + " | " + sr.getResponseMessage()).trim();
                    errorTypeCount.merge(key, 1L, Long::sum);
                }

                long sampleStart = sr.getTimeStamp();
                long sampleEnd   = sampleStart + sr.getTime();
                if (sampleStart < testStartMs) testStartMs = sampleStart;
                if (sampleEnd   > testEndMs)   testEndMs   = sampleEnd;

                long bucketKey = (sampleStart / bucketSizeMs) * bucketSizeMs;
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

        // Derive average Latency and Connect from the accumulated totals.
        // Divide by totalSampleCount (not latencySampleCount) so the averages are
        // comparable to avgResponseMs — i.e. averaged over all samples, not just
        // those where Latency > 0.  latencyPresent drives the prompt's branch logic.
        final long totalSampleCount = totalCalc.getCount();
        final long avgLatencyMs     = (latencySampleCount > 0 && totalSampleCount > 0)
                ? totalLatencyMs / totalSampleCount : 0L;
        final long avgConnectMs     = (latencySampleCount > 0 && totalSampleCount > 0)
                ? totalConnectMs / totalSampleCount : 0L;
        final boolean latencyPresent = latencySampleCount > 0;

        List<TimeBucket> timeBuckets = JtlParserCore.buildTimeBuckets(bucketMap, bucketSizeMs);
        log.info("parse: completed. labels={}, samples={}, buckets={}, latencyPresent={}",
                results.size(), totalCalc.getCount(), timeBuckets.size(), latencyPresent);
        return new ParseResult(results, testStartMs, testEndMs, timeBuckets, errorTypeCount,
                avgLatencyMs, avgConnectMs, latencyPresent);
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
         * Top-5 failure types by frequency, each entry containing:
         * {@code responseCode}, {@code responseMessage}, {@code count}.
         * Empty list when no failures occurred.
         */
        public final List<Map<String, Object>> errorTypeSummary;
        /**
         * Average Latency (TTFB) in milliseconds across all filtered samples.
         * Zero when the {@code Latency} column is absent from the JTL or every
         * row has {@code Latency = 0} (i.e. {@link #latencyPresent} is false).
         */
        public final long avgLatencyMs;
        /**
         * Average Connect time in milliseconds across all filtered samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgConnectMs;
        /**
         * {@code true} when at least one sample has a non-zero {@code Latency} value.
         * Drives the {@code latencyPresent} branch in the AI prompt's
         * Advanced Web Diagnostics section.
         */
        public final boolean latencyPresent;

        private static final int MAX_ERROR_TYPES = 5;

        /**
         * Shared display format — matches the format used in the GUI table and CLI output.
         * Single source of truth: avoids the duplicated {@code DateTimeFormatter} constants
         * that previously lived in {@code AggregateReportPanel} and {@code CliReportPipeline}.
         */
        private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
                DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");

        /**
         * Returns the test start time as a formatted display string, or an empty string
         * if {@link #startTimeMs} is zero.
         *
         * @return formatted start time, e.g. {@code "03/11/26 14:30:00"}, or {@code ""}
         */
        public String formattedStartTime() {
            return startTimeMs > 0 ? formatEpochMs(startTimeMs) : "";
        }

        /**
         * Returns the test end time as a formatted display string, or an empty string
         * if {@link #endTimeMs} is zero.
         *
         * @return formatted end time, e.g. {@code "03/11/26 15:00:00"}, or {@code ""}
         */
        public String formattedEndTime() {
            return endTimeMs > 0 ? formatEpochMs(endTimeMs) : "";
        }

        /**
         * Returns the test duration as a formatted display string, or an empty string
         * if {@link #durationMs} is zero.
         *
         * @return formatted duration, e.g. {@code "0h 30m 0s"}, or {@code ""}
         */
        public String formattedDuration() {
            if (durationMs <= 0) return "";
            long s = durationMs / 1000;
            return String.format("%dh %dm %ds", s / 3600, (s % 3600) / 60, s % 60);
        }

        private static String formatEpochMs(long epochMs) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                    .format(DISPLAY_TIME_FORMAT);
        }

        /**
         * Constructs a parse result.
         *
         * @param results        per-label aggregated statistics
         * @param startTimeMs    epoch millis of first sample
         * @param endTimeMs      epoch millis of last sample end
         * @param timeBuckets    ordered list of 30-second time buckets
         * @param errorTypeCount raw "responseCode | responseMessage" → count map
         * @param avgLatencyMs   average Latency ms (0 if absent or all-zero)
         * @param avgConnectMs   average Connect ms (0 if latencyPresent is false)
         * @param latencyPresent true iff ≥ 1 non-zero Latency value was seen
         */
        public ParseResult(Map<String, SamplingStatCalculator> results,
                           long startTimeMs, long endTimeMs,
                           List<TimeBucket> timeBuckets,
                           Map<String, Long> errorTypeCount,
                           long avgLatencyMs, long avgConnectMs, boolean latencyPresent) {
            this.results        = results;
            this.startTimeMs    = startTimeMs;
            this.endTimeMs      = endTimeMs;
            this.durationMs     = Math.max(0, endTimeMs - startTimeMs);
            this.timeBuckets    = timeBuckets != null ? timeBuckets : Collections.emptyList();
            this.errorTypeSummary = buildErrorTypeSummary(errorTypeCount);
            this.avgLatencyMs   = avgLatencyMs;
            this.avgConnectMs   = avgConnectMs;
            this.latencyPresent = latencyPresent;
        }

        private static List<Map<String, Object>> buildErrorTypeSummary(
                Map<String, Long> errorTypeCount) {
            if (errorTypeCount == null || errorTypeCount.isEmpty())
                return Collections.emptyList();

            return errorTypeCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_ERROR_TYPES)
                    .map(e -> {
                        String[] parts = e.getKey().split(" \\| ", 2);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("responseCode",    parts[0].trim());
                        m.put("responseMessage", parts.length > 1 ? parts[1].trim() : "");
                        m.put("count",           e.getValue());
                        return m;
                    })
                    .toList();
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

        /**
         * Chart time-bucket interval in seconds. {@code 0} (default) means auto-calculate
         * using the built-in 30-second bucket size.
         *
         * <p>When the user sets a positive value via the "Chart Interval" field, the
         * parser uses that value (converted to milliseconds) as the bucket width for
         * time-series charts.</p>
         */
        public int chartIntervalSeconds = 0;
    }
}