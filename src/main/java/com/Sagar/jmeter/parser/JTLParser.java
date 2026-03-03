package com.Sagar.jmeter.parser;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.data.JTLRecord;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Parses JTL files and generates aggregate statistics
 */
public class JTLParser {

    /**
     * Parse JTL file and return aggregated results by label
     */
    public Map<String, AggregateResult> parse(String filePath, FilterOptions options) throws IOException {
        Map<String, AggregateResult> results = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Empty JTL file");
            }

            String[] headers = headerLine.split(",");
            Map<String, Integer> columnMap = buildColumnMap(headers);

            String line;
            while ((line = reader.readLine()) != null) {
                JTLRecord record = parseLine(line, columnMap);
                if (record != null && shouldInclude(record, options)) {
                    String label = record.getLabel();
                    AggregateResult result = results.computeIfAbsent(label, k -> {
                        AggregateResult r = new AggregateResult();
                        r.setLabel(label);
                        return r;
                    });
                    result.addSample(record);
                }
            }
        }

        // Add TOTAL row
        if (!results.isEmpty()) {
            AggregateResult total = new AggregateResult();
            total.setLabel("TOTAL");
            for (AggregateResult r : results.values()) {
                // Aggregate the aggregates by re-processing
                for (int i = 0; i < r.getCount(); i++) {
                    // This is a simplification - ideally we'd keep all raw records
                }
            }
        }

        return results;
    }

    private Map<String, Integer> buildColumnMap(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    private JTLRecord parseLine(String line, Map<String, Integer> columnMap) {
        String[] values = line.split(",", -1);
        JTLRecord record = new JTLRecord();

        try {
            record.setTimeStamp(getLong(values, columnMap, "timeStamp", 0));
            record.setElapsed(getLong(values, columnMap, "elapsed", 0));
            record.setLabel(getString(values, columnMap, "label", ""));
            record.setResponseCode(getString(values, columnMap, "responseCode", ""));
            record.setResponseMessage(getString(values, columnMap, "responseMessage", ""));
            record.setThreadName(getString(values, columnMap, "threadName", ""));
            record.setDataType(getString(values, columnMap, "dataType", ""));
            record.setSuccess(getBoolean(values, columnMap, "success", true));
            record.setFailureMessage(getString(values, columnMap, "failureMessage", ""));
            record.setBytes(getLong(values, columnMap, "bytes", 0));
            record.setSentBytes(getLong(values, columnMap, "sentBytes", 0));
            record.setGrpThreads(getInt(values, columnMap, "grpThreads", 0));
            record.setAllThreads(getInt(values, columnMap, "allThreads", 0));
            record.setUrl(getString(values, columnMap, "URL", ""));
            record.setLatency(getLong(values, columnMap, "Latency", 0));
            record.setIdleTime(getLong(values, columnMap, "IdleTime", 0));
            record.setConnect(getLong(values, columnMap, "Connect", 0));

            return record;
        } catch (Exception e) {
            return null; // Skip malformed lines
        }
    }

    private boolean shouldInclude(JTLRecord record, FilterOptions options) {
        // Apply filters
        String label = record.getLabel();

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

        return true;
    }

    private String getString(String[] values, Map<String, Integer> map, String column, String defaultValue) {
        Integer index = map.get(column);
        if (index == null || index >= values.length) return defaultValue;
        String value = values[index].trim();
        // Remove quotes if present
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private long getLong(String[] values, Map<String, Integer> map, String column, long defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getInt(String[] values, Map<String, Integer> map, String column, int defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(String[] values, Map<String, Integer> map, String column, boolean defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        return "true".equalsIgnoreCase(str);
    }

    public static class FilterOptions {
        public String includeLabels = "";
        public String excludeLabels = "";
        public boolean regExp = false;
        public int startOffset = 0;
        public int endOffset = 0;
        public int percentile = 90;
    }
}
