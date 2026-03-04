package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses JTL (CSV) files and generates aggregate statistics using
 * {@link SamplingStatCalculator} — the same engine as JMeter's Aggregate Report.
 *
 * <p>Supports filtering by time offset and label patterns.</p>
 */
public class JTLParser {

    private static final String TOTAL_LABEL = "TOTAL";

    /**
     * Result of parsing a JTL file — includes aggregated stats plus time metadata.
     */
    public static class ParseResult {
        /** Per-label calculators with TOTAL row last. */
        public final Map<String, SamplingStatCalculator> results;
        /** Timestamp (ms) of the earliest sample start. */
        public final long startTimeMs;
        /** Timestamp (ms) of the latest sample end (timestamp + elapsed). */
        public final long endTimeMs;
        /** Test duration in milliseconds (endTimeMs - startTimeMs). */
        public final long durationMs;

        public ParseResult(Map<String, SamplingStatCalculator> results,
                           long startTimeMs, long endTimeMs) {
            this.results = results;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.durationMs = Math.max(0, endTimeMs - startTimeMs);
        }
    }

    /**
     * Parse JTL file and return aggregated results with time metadata.
     *
     * @param filePath path to the JTL CSV file
     * @param options  filter and display options
     * @return ParseResult containing stats map + start/end/duration
     * @throws IOException if the file cannot be read
     */
    public ParseResult parse(String filePath, FilterOptions options) throws IOException {
        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        SamplingStatCalculator totalCalc = new SamplingStatCalculator(TOTAL_LABEL);

        // First pass: find min timestamp (for offset filtering) and collect all labels
        long minTimestamp = Long.MAX_VALUE;
        java.util.Set<String> allLabels = new java.util.HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Empty JTL file");
            }

            Map<String, Integer> columnMap = buildColumnMap(headerLine.split(","));
            Integer tsIndex = columnMap.get("timeStamp");
            Integer labelIndex = columnMap.get("label");

            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] values = splitCsvLine(line);
                    if (labelIndex != null && labelIndex < values.length) {
                        allLabels.add(values[labelIndex].trim());
                    }
                    if (tsIndex != null && tsIndex < values.length) {
                        long ts = Long.parseLong(values[tsIndex].trim());
                        if (ts > 0 && ts < minTimestamp) {
                            minTimestamp = ts;
                        }
                    }
                } catch (NumberFormatException e) {
                    // skip malformed lines
                }
            }
        }
        options.minTimestamp = (minTimestamp == Long.MAX_VALUE) ? 0 : minTimestamp;

        // Build sub-result label set
        java.util.Set<String> subResultLabels = new java.util.HashSet<>();
        for (String label : allLabels) {
            int lastDash = label.lastIndexOf('-');
            if (lastDash > 0 && lastDash < label.length() - 1) {
                String suffix = label.substring(lastDash + 1);
                String parentCandidate = label.substring(0, lastDash);
                if (isNumeric(suffix) && allLabels.contains(parentCandidate)) {
                    subResultLabels.add(label);
                }
            }
        }

        // Second pass: parse, filter, aggregate — and track start/end times
        long testStartMs = Long.MAX_VALUE;
        long testEndMs   = Long.MIN_VALUE;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Empty JTL file");
            }

            Map<String, Integer> columnMap = buildColumnMap(headerLine.split(","));

            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    SampleResult sr = parseLine(line, columnMap);
                    if (sr != null
                            && !subResultLabels.contains(sr.getSampleLabel())
                            && shouldInclude(sr, columnMap, line, options)) {
                        String label = sr.getSampleLabel();

                        SamplingStatCalculator calc = results.computeIfAbsent(label,
                                SamplingStatCalculator::new);
                        synchronized (calc) {
                            calc.addSample(sr);
                        }
                        synchronized (totalCalc) {
                            totalCalc.addSample(sr);
                        }

                        // Track earliest start and latest end
                        long sampleStart = sr.getTimeStamp();
                        long sampleEnd   = sampleStart + sr.getTime();
                        if (sampleStart < testStartMs) testStartMs = sampleStart;
                        if (sampleEnd > testEndMs)     testEndMs = sampleEnd;
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        }

        if (!results.isEmpty()) {
            results.put(TOTAL_LABEL, totalCalc);
        }

        // Normalize if no samples matched
        if (testStartMs == Long.MAX_VALUE) testStartMs = 0;
        if (testEndMs == Long.MIN_VALUE)   testEndMs = 0;

        return new ParseResult(results, testStartMs, testEndMs);
    }

    /**
     * Check if a string is a non-negative integer (used for sub-result suffix detection).
     */
    private boolean isNumeric(String str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // CSV parsing
    // ─────────────────────────────────────────────────────────────

    private Map<String, Integer> buildColumnMap(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    /**
     * Parse one CSV line directly into a {@link SampleResult}.
     * No intermediate JTLRecord needed.
     */
    private SampleResult parseLine(String line, Map<String, Integer> columnMap) {
        if (line == null || line.isBlank()) {
            return null;
        }

        String[] values = splitCsvLine(line);
        SampleResult sr = new SampleResult();

        try {
            sr.setTimeStamp(getLong(values, columnMap, "timeStamp", 0));

            long elapsed = getLong(values, columnMap, "elapsed", 0);
            sr.setStampAndTime(sr.getTimeStamp(), elapsed);

            sr.setSampleLabel(getString(values, columnMap, "label", "unknown"));
            sr.setResponseCode(getString(values, columnMap, "responseCode", ""));
            sr.setResponseMessage(getString(values, columnMap, "responseMessage", ""));
            sr.setThreadName(getString(values, columnMap, "threadName", ""));
            sr.setDataType(getString(values, columnMap, "dataType", ""));
            sr.setSuccessful("true".equalsIgnoreCase(
                    getString(values, columnMap, "success", "true")));

            sr.setBytes((int) getLong(values, columnMap, "bytes", 0));
            sr.setSentBytes(getLong(values, columnMap, "sentBytes", 0));
            sr.setLatency(getLong(values, columnMap, "Latency", 0));
            sr.setIdleTime(getLong(values, columnMap, "IdleTime", 0));
            sr.setConnectTime(getLong(values, columnMap, "Connect", 0));

            return sr;
        } catch (Exception e) {
            return null; // Skip malformed lines
        }
    }

    /**
     * Split a CSV line respecting quoted fields.
     * Handles cases like responseMessage containing commas.
     */
    private String[] splitCsvLine(String line) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
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
    // Filtering
    // ─────────────────────────────────────────────────────────────

    private boolean shouldInclude(SampleResult sr, Map<String, Integer> columnMap,
                                  String rawLine, FilterOptions options) {
        String label = sr.getSampleLabel();

        // Check if label should be included
        if (options.includeLabels != null && !options.includeLabels.isEmpty()) {
            if (options.regExp) {
                if (!label.matches(options.includeLabels)) {
                    return false;
                }
            } else {
                if (!label.contains(options.includeLabels)) {
                    return false;
                }
            }
        }

        // Check if label should be excluded
        if (options.excludeLabels != null && !options.excludeLabels.isEmpty()) {
            if (options.regExp) {
                if (label.matches(options.excludeLabels)) {
                    return false;
                }
            } else {
                if (label.contains(options.excludeLabels)) {
                    return false;
                }
            }
        }

        // Apply timestamp filters (offset in seconds relative to test start)
        if (options.startOffset > 0 || options.endOffset > 0) {
            long timestampMs = sr.getTimeStamp();
            long relativeTimeSec = (timestampMs - options.minTimestamp) / 1000L;

            if (options.startOffset > 0 && relativeTimeSec < options.startOffset) {
                return false;
            }
            if (options.endOffset > 0 && relativeTimeSec > options.endOffset) {
                return false;
            }
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Field extraction helpers
    // ─────────────────────────────────────────────────────────────

    private String getString(String[] values, Map<String, Integer> map,
                             String column, String defaultValue) {
        Integer index = map.get(column);
        if (index == null || index >= values.length) return defaultValue;
        String value = values[index].trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
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
     * Filter and display options for JTL parsing.
     */
    public static class FilterOptions {
        public String includeLabels = "";
        public String excludeLabels = "";
        public boolean regExp = false;
        public int startOffset = 0;
        public int endOffset = 0;
        public int percentile = 90;
        public long minTimestamp = 0;  // Internal: tracks test start time
    }
}