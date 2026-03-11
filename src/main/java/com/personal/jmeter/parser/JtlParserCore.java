package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Low-level CSV parsing and aggregation helpers for {@link JTLParser}.
 *
 * <p>Extracted from {@link JTLParser} to satisfy the 300-line class design
 * limit (Standard 3 SRP). Responsibility: per-line parsing and data
 * transformation only — no file I/O, no public API surface.</p>
 *
 * <p>All methods are package-private statics; callers do not need an instance.</p>
 */
final class JtlParserCore {

    private static final Logger log = LoggerFactory.getLogger(JtlParserCore.class);

    private JtlParserCore() { /* static utility — not instantiable */ }

    // ─────────────────────────────────────────────────────────────
    // Bucket helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Converts bucket accumulators into an ordered {@link JTLParser.TimeBucket} list.
     *
     * @param bucketMap raw accumulator map from Pass 2 (key = bucket epoch ms)
     * @param bucketSizeMs bucket interval in milliseconds
     * @return ordered list of time buckets
     */
    static List<JTLParser.TimeBucket> buildTimeBuckets(TreeMap<Long, long[]> bucketMap,
                                                       long bucketSizeMs) {
        List<JTLParser.TimeBucket> list = new ArrayList<>(bucketMap.size());
        double bucketSec = bucketSizeMs / 1000.0;
        for (Map.Entry<Long, long[]> e : bucketMap.entrySet()) {
            long[] acc  = e.getValue();
            long count  = acc[1];
            double avgRt  = count > 0 ? (double) acc[0] / count : 0.0;
            double errPct = count > 0 ? (double) acc[2] / count * 100.0 : 0.0;
            double tps    = count / bucketSec;
            double kbps   = (double) acc[3] / bucketSec / 1024.0;
            list.add(new JTLParser.TimeBucket(e.getKey(), avgRt, errPct, tps, kbps));
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────
    // CSV parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a column-name-to-index map from the JTL header line.
     *
     * @param headers raw header tokens (split on comma before call)
     * @return map from trimmed column name to its zero-based index
     */
    static Map<String, Integer> buildColumnMap(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    /**
     * Parses one CSV line into a {@link SampleResult}.
     * Returns {@code null} for blank lines or lines that cannot be parsed.
     *
     * @param line   one CSV data line (not the header)
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return populated {@link SampleResult}, or {@code null} if the line is invalid
     */
    static SampleResult parseLine(String line, Map<String, Integer> colMap) {
        if (line == null || line.isBlank()) return null;
        try {
            String[] v = splitCsvLine(line);
            SampleResult sr = new SampleResult();
            sr.setTimeStamp(getLong(v, colMap, "timeStamp", 0));
            // NOTE: setStampAndTime() is intentionally NOT called here.
            // The caller (JTLParser.parse) calls it exactly once, after optionally
            // adjusting the elapsed value, to avoid the IllegalStateException that
            // SampleResult throws when setStampAndTime() is called more than once.
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
     * Extracts the raw {@code elapsed} value from a CSV line.
     * Companion to {@link #parseLine} — used by the caller to obtain elapsed
     * before calling {@code SampleResult.setStampAndTime()} exactly once.
     *
     * @param line   one CSV data line (not the header)
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return elapsed in milliseconds, or {@code 0} if absent/malformed
     */
    static long parseElapsed(String line, Map<String, Integer> colMap) {
        if (line == null || line.isBlank()) return 0;
        String[] v = splitCsvLine(line);
        return getLong(v, colMap, "elapsed", 0);
    }

    /**
     *
     * @param line raw CSV line
     * @return array of unquoted, trimmed field values
     */
    static String[] splitCsvLine(String line) {
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

    // ─────────────────────────────────────────────────────────────
    // Filter
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code sr} should be included based on {@code options}.
     *
     * @param sr      the sample to test
     * @param options current filter options
     * @return {@code true} to include, {@code false} to exclude
     */
    static boolean shouldInclude(SampleResult sr, JTLParser.FilterOptions options) {
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
            if (options.endOffset   > 0 && relativeSec > options.endOffset)   return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Field extraction helpers
    // ─────────────────────────────────────────────────────────────

    static String getString(String[] values, Map<String, Integer> map,
                            String column, String defaultValue) {
        Integer index = map.get(column);
        if (index == null || index >= values.length) return defaultValue;
        String value = values[index].trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    static long getLong(String[] values, Map<String, Integer> map,
                        String column, long defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}