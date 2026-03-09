package com.personal.jmeter.ai;

import com.personal.jmeter.parser.JTLParser;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Converts an AI-generated Markdown report into a styled, standalone HTML file
 * and writes it next to the source JTL file.
 *
 * <p>Report layout:
 * <ol>
 *   <li>Cover header — scenario metadata</li>
 *   <li>AI-generated analysis (Markdown → HTML)</li>
 *   <li>Transaction Metrics table</li>
 *   <li>Performance Charts (Chart.js)</li>
 * </ol>
 *
 * <p>{@link #buildTransactionMetricsSection(List)} and {@link #escapeHtml(String)}
 * are package-accessible for unit testing without touching the file system.</p>
 */
public class HtmlReportRenderer {

    /**
     * Index of the configurable-percentile column in {@link #TABLE_HEADERS}.
     */
    static final int PERCENTILE_COL_INDEX = 7;
    /**
     * Column headers for the Transaction Metrics table.
     * Index {@value #PERCENTILE_COL_INDEX} ("90th Pct (ms)") matches the default percentile.
     * {@link #buildTableHeaders(int)} clones this array and updates index
     * {@value #PERCENTILE_COL_INDEX} when a different percentile is configured.
     */
    static final String[] TABLE_HEADERS = {
            "Transaction", "Count", "Passed", "Failed",
            "Avg RT (ms)", "Min RT (ms)", "Max RT (ms)", "90th Pct (ms)",
            "Std Dev", "Error Rate", "TPS"
    };
    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);
    private static final String TD_CLOSE = "</td>";

    private static String buildRunDateTime(String startTime, String endTime) {
        boolean hasStart = startTime != null && !startTime.isBlank();
        boolean hasEnd = endTime != null && !endTime.isBlank();
        if (hasStart && hasEnd) return startTime.trim() + " - " + endTime.trim();
        if (hasStart) return startTime.trim();
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    private static void appendMetaRow(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("  <tr>")
                .append("<td class=\"meta-label\">").append(escapeHtml(label)).append(TD_CLOSE)
                .append("<td class=\"meta-value\">").append(escapeHtml(value)).append(TD_CLOSE)
                .append("</tr>\n");
    }

    // ─────────────────────────────────────────────────────────────
    // Transaction Metrics section (package-private for unit tests)
    // ─────────────────────────────────────────────────────────────

    private static String chartBox(String canvasId, String title) {
        return new StringBuilder(256)
                .append("  <div class=\"chart-box\">\n")
                .append("    <h3>").append(title).append("</h3>\n")
                .append("    <div class=\"chart-canvas-wrap\"><canvas id=\"").append(canvasId).append("\"></canvas></div>\n")
                .append("  </div>\n")
                .toString();
    }

    private static String buildTimeSeriesChartFn() {
        return new StringBuilder(1024)
                .append("  function timeChart(id, data, label, unit, color) {\n")
                .append("    new Chart(document.getElementById(id), {\n")
                .append("      type: 'line',\n")
                .append("      data: { labels: labels, datasets: [{\n")
                .append("        label: label, data: data, borderColor: color,\n")
                .append("        backgroundColor: color.replace('1)', '0.10)'),\n")
                .append("        borderWidth: 2, pointRadius: 3, pointHoverRadius: 6,\n")
                .append("        fill: true, tension: 0.25\n")
                .append("      }]},\n")
                .append("      options: {\n")
                .append("        responsive: true, maintainAspectRatio: false,\n")
                .append("        plugins: {\n")
                .append("          legend: { display: false },\n")
                .append("          tooltip: { callbacks: { label: function(ctx) {\n")
                .append("            return ' ' + ctx.parsed.y.toFixed(2) + ' ' + unit;\n")
                .append("          }}}\n")
                .append("        },\n")
                .append("        scales: {\n")
                .append("          x: { title: { display: true, text: 'Test Time (HH:mm:ss)', font: { size: 11 } },\n")
                .append("               ticks: { font: { size: 10 }, maxRotation: 45, autoSkip: true, maxTicksLimit: 15 },\n")
                .append("               grid: { color: 'rgba(0,0,0,0.04)' } },\n")
                .append("          y: { beginAtZero: true,\n")
                .append("               title: { display: true, text: unit, font: { size: 11 } },\n")
                .append("               ticks: { font: { size: 11 } },\n")
                .append("               grid: { color: 'rgba(0,0,0,0.04)' } }\n")
                .append("        }\n")
                .append("      }\n")
                .append("    });\n")
                .append("  }\n")
                .toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Page assembly
    // ─────────────────────────────────────────────────────────────

    private static String buildCss() {
        return new StringBuilder(3072)
                .append("  <style>\n")
                .append("    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n")
                .append("    body { font-family: 'Segoe UI', system-ui, sans-serif; font-size: 14px;\n")
                .append("           line-height: 1.7; color: #1a202c; background: #f7f8fc; }\n")
                .append("    .report-header { background: linear-gradient(135deg, #1a365d 0%, #2b6cb0 100%);\n")
                .append("                     color: white; padding: 32px 48px 28px; }\n")
                .append("    .report-header h1 { font-size: 24px; font-weight: 700; margin-bottom: 16px; }\n")
                .append("    .report-header .meta-table { border-collapse: collapse; background: transparent;\n")
                .append("                                  box-shadow: none; border-radius: 0; margin: 0; width: auto; }\n")
                .append("    .report-header .meta-table td { padding: 3px 0; font-size: 13px;\n")
                .append("                                     border-bottom: none; background: transparent !important; }\n")
                .append("    .report-header .meta-table tr:nth-child(even) td { background: transparent !important; }\n")
                .append("    .report-header .meta-table .meta-label { color: rgba(255,255,255,0.70); font-weight: 600;\n")
                .append("                                              padding-right: 16px; white-space: nowrap; }\n")
                .append("    .report-header .meta-table .meta-value { color: white; }\n")
                .append("    .content { max-width: 1000px; margin: 32px auto; padding: 0 24px; }\n")
                .append("    h1 { display: none; }\n")
                .append("    h2 { font-size: 17px; font-weight: 700; color: #1a365d;\n")
                .append("         border-left: 4px solid #3182ce; padding-left: 12px;\n")
                .append("         margin: 32px 0 12px; }\n")
                .append("    h3 { font-size: 14px; font-weight: 600; color: #2d3748; margin: 18px 0 8px; }\n")
                .append("    p  { margin-bottom: 12px; }\n")
                .append("    table { border-collapse: collapse; width: 100%; margin: 14px 0 20px;\n")
                .append("            background: white; border-radius: 6px; overflow: hidden;\n")
                .append("            box-shadow: 0 1px 4px rgba(0,0,0,0.08); }\n")
                .append("    th { background: #2d3748; color: white; padding: 9px 14px;\n")
                .append("         text-align: left; font-size: 12px; font-weight: 600;\n")
                .append("         text-transform: uppercase; letter-spacing: 0.5px; }\n")
                .append("    td { padding: 8px 14px; border-bottom: 1px solid #edf2f7; font-size: 13px; }\n")
                .append("    td.num { text-align: right; font-variant-numeric: tabular-nums; }\n")
                .append("    tr:last-child td { border-bottom: none; }\n")
                .append("    tr:nth-child(even) td { background: #f7fafc; }\n")
                .append("    code { background: #edf2f7; padding: 2px 6px; border-radius: 4px;\n")
                .append("           font-family: Consolas, monospace; font-size: 12px; color: #c53030; }\n")
                .append("    pre  { background: #2d3748; color: #e2e8f0; padding: 14px 18px;\n")
                .append("           border-radius: 6px; overflow-x: auto; margin: 12px 0 18px; }\n")
                .append("    pre code { background: none; color: inherit; padding: 0; }\n")
                .append("    blockquote { border-left: 4px solid #63b3ed; margin: 14px 0;\n")
                .append("                 padding: 8px 14px; background: #ebf8ff;\n")
                .append("                 border-radius: 0 6px 6px 0; }\n")
                .append("    ul, ol { margin: 8px 0 14px 24px; }\n")
                .append("    li { margin-bottom: 4px; }\n")
                .append("    strong { font-weight: 600; }\n")
                .append("    .metrics-section { margin: 40px 0 0; }\n")
                .append("    .charts-section  { margin: 40px 0 0; }\n")
                .append("    .charts-note { font-size: 12px; color: #718096; margin-bottom: 20px; }\n")
                .append("    .chart-box { background: white; border-radius: 8px; padding: 20px 24px 16px;\n")
                .append("                 box-shadow: 0 1px 4px rgba(0,0,0,0.08); margin-bottom: 24px; }\n")
                .append("    .chart-box h3 { font-size: 13px; font-weight: 600; color: #2d3748;\n")
                .append("                    margin: 0 0 14px; border: none; padding: 0; }\n")
                .append("    .chart-canvas-wrap { position: relative; height: 280px; }\n")
                .append("    .footer { text-align: center; font-size: 11px; color: #a0aec0;\n")
                .append("              margin: 48px 0 24px; padding-top: 14px;\n")
                .append("              border-top: 1px solid #e2e8f0; }\n")
                .append("    @media print { .report-header { background: #1a365d !important;\n")
                .append("                   -webkit-print-color-adjust: exact; }\n")
                .append("                   body { background: white; } }\n")
                .append("  </style>\n")
                .toString();
    }

    /**
     * Converts GFM pipe tables embedded in {@code text} to HTML {@code <table>} blocks.
     * Cell content is HTML-escaped to prevent injection of AI-generated markup.
     *
     * @param text Markdown text potentially containing pipe tables
     * @return text with pipe tables replaced by HTML table markup
     */
    private static String convertPipeTablesToHtml(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (i + 1 < lines.length && isPipeRow(lines[i]) && isSeparatorRow(lines[i + 1])) {
                List<String[]> tableLines = new ArrayList<>();
                tableLines.add(splitPipeRow(lines[i]));
                i += 2;
                while (i < lines.length && isPipeRow(lines[i])) {
                    tableLines.add(splitPipeRow(lines[i++]));
                }
                out.append(renderPipeTable(tableLines));
            } else {
                out.append(lines[i++]).append("\n");
            }
        }
        return out.toString();
    }

    private static String renderPipeTable(List<String[]> tableLines) {
        StringBuilder sb = new StringBuilder("<table>\n<thead>\n<tr>");
        for (String cell : tableLines.get(0)) {
            sb.append("<th>").append(escapeHtml(cell.trim())).append("</th>");
        }
        sb.append("</tr>\n</thead>\n<tbody>\n");
        for (int r = 1; r < tableLines.size(); r++) {
            sb.append("<tr>");
            for (String cell : tableLines.get(r)) {
                sb.append("<td>").append(escapeHtml(cell.trim())).append(TD_CLOSE);
            }
            sb.append("</tr>\n");
        }
        return sb.append("</tbody>\n</table>\n").toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Chart section
    // ─────────────────────────────────────────────────────────────

    private static boolean isPipeRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|") && t.length() > 2;
    }

    private static boolean isSeparatorRow(String line) {
        String t = line.trim();
        if (!t.startsWith("|") || !t.endsWith("|")) return false;
        return t.replace("|", "").replaceAll("[\\-:\\s]", "").isEmpty();
    }

    private static String[] splitPipeRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        return t.split("\\|", -1);
    }

    // ─────────────────────────────────────────────────────────────
    // CSS
    // ─────────────────────────────────────────────────────────────

    /**
     * Escapes {@code &}, {@code <}, and {@code >} for safe HTML embedding.
     *
     * @param s input string (may be null)
     * @return escaped string, or empty string for null input
     */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ─────────────────────────────────────────────────────────────
    // Markdown helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a clone of {@link #TABLE_HEADERS} with the percentile column updated.
     *
     * @param percentile configured percentile value (e.g. 95)
     * @return header array with updated percentile label
     */
    private static String[] buildTableHeaders(int percentile) {
        String[] headers = TABLE_HEADERS.clone();
        headers[PERCENTILE_COL_INDEX] = percentile + "th Pct (ms)";
        return headers;
    }

    private static String deriveOutputPath(String jtlFilePath, String scenarioName,
                                           String threadGroupName) {
        String dir = Path.of(jtlFilePath).toAbsolutePath().getParent().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String planPart = sanitizeSegment(scenarioName);
        String tgPart = sanitizeSegment(threadGroupName);

        StringBuilder name = new StringBuilder("AI_Generated_Report");
        if (!planPart.isEmpty()) name.append('_').append(planPart);
        if (!tgPart.isEmpty()) name.append('_').append(tgPart);
        name.append('_').append(timestamp).append(".html");

        return dir + java.io.File.separator + name;
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim()
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Renders the full AI report to an HTML file on disk.
     *
     * @param markdownContent AI-generated report in Markdown; must not be null
     * @param jtlFilePath     path to the source JTL file (output placed next to it); must not be null
     * @param config          scenario metadata; must not be null
     * @param tableRows       visible table rows from the plugin (TOTAL excluded)
     * @param timeBuckets     30-second time buckets from the JTL parser
     * @return absolute path of the written HTML file
     * @throws IOException if the file cannot be written
     */
    public String render(String markdownContent,
                         String jtlFilePath,
                         RenderConfig config,
                         List<String[]> tableRows,
                         List<JTLParser.TimeBucket> timeBuckets) throws IOException {
        Objects.requireNonNull(markdownContent, "markdownContent must not be null");
        Objects.requireNonNull(jtlFilePath, "jtlFilePath must not be null");
        Objects.requireNonNull(config, "config must not be null");

        log.info("render: generating HTML report. jtlFile={}", jtlFilePath);

        String htmlBody = markdownToHtml(markdownContent);
        String metricsTable = buildTransactionMetricsSection(tableRows, config.percentile);
        String chartsBlock = buildChartsSection(timeBuckets);
        String page = buildPage(htmlBody, metricsTable, chartsBlock, config);

        String outPath = deriveOutputPath(jtlFilePath, config.scenarioName, config.threadGroupName);
        Files.writeString(Path.of(outPath), page, StandardCharsets.UTF_8);
        log.info("render: HTML report written. outPath={}", outPath);
        return outPath;
    }

    /**
     * Convenience overload used by tests — defaults percentile to 90.
     *
     * @param rows data rows (TOTAL excluded)
     * @return HTML string for the metrics section, or empty string if rows is empty
     */
    String buildTransactionMetricsSection(List<String[]> rows) {
        return buildTransactionMetricsSection(rows, 90);
    }

    /**
     * Builds the Transaction Metrics HTML section.
     * Returns an empty string when {@code rows} is {@code null} or empty.
     *
     * @param rows       data rows to render
     * @param percentile configurable percentile label
     * @return HTML section string
     */
    String buildTransactionMetricsSection(List<String[]> rows, int percentile) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }

        String[] headers = buildTableHeaders(percentile);
        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"metrics-section\">\n")
                .append("  <h2>Transaction Metrics</h2>\n")
                .append("  <table>\n")
                .append("    <thead><tr>\n");

        for (String h : headers) {
            sb.append("      <th>").append(escapeHtml(h)).append("</th>\n");
        }
        sb.append("    </tr></thead>\n").append("    <tbody>\n");

        for (String[] row : rows) {
            sb.append("    <tr>\n");
            for (int i = 0; i < headers.length; i++) {
                String cell = (row != null && i < row.length && row[i] != null) ? row[i] : "";
                String align = (i == 0) ? "" : " class=\"num\"";
                sb.append("      <td").append(align).append(">")
                        .append(escapeHtml(cell))
                        .append(TD_CLOSE).append("\n");
            }
            sb.append("    </tr>\n");
        }
        sb.append("    </tbody>\n").append("  </table>\n").append("</div>\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Public utilities
    // ─────────────────────────────────────────────────────────────

    private String buildPage(String htmlBody, String metricsTable,
                             String chartsBlock, RenderConfig config) {
        String runDateTime = buildRunDateTime(config.startTime, config.endTime);

        StringBuilder meta = new StringBuilder("<table class=\"meta-table\">\n");
        appendMetaRow(meta, "Scenario Name", config.scenarioName);
        appendMetaRow(meta, "Scenario Description", config.scenarioDesc);
        appendMetaRow(meta, "Virtual Users", config.users);
        if (!runDateTime.isEmpty()) appendMetaRow(meta, "Run Date/Time", runDateTime);
        appendMetaRow(meta, "Duration", config.duration);
        meta.append("</table>\n");

        return new StringBuilder(8192)
                .append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("  <meta charset=\"UTF-8\">\n")
                .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("  <title>AI Performance Report</title>\n")
                .append("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js\"></script>\n")
                .append(buildCss())
                .append("</head>\n<body>\n")
                .append("<div class=\"report-header\">\n")
                .append("  <h1>JMeter AI Performance Report</h1>\n")
                .append("  ").append(meta)
                .append("</div>\n<div class=\"content\">\n")
                .append(htmlBody).append("\n")
                .append(metricsTable)
                .append(chartsBlock)
                .append("  <div class=\"footer\">Generated by Configurable Aggregate Report Plugin")
                .append(" &mdash; AI Report Feature</div>\n")
                .append("</div>\n</body>\n</html>\n")
                .toString();
    }

    private String buildChartsSection(List<JTLParser.TimeBucket> timeBuckets) {
        if (timeBuckets == null || timeBuckets.isEmpty()) return "";

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        List<String> jLabels = new ArrayList<>();
        List<String> jAvg = new ArrayList<>();
        List<String> jErr = new ArrayList<>();
        List<String> jTps = new ArrayList<>();
        List<String> jKb = new ArrayList<>();

        for (JTLParser.TimeBucket b : timeBuckets) {
            String label = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(b.epochMs), ZoneId.systemDefault()).format(timeFmt);
            jLabels.add("\"" + label + "\"");
            jAvg.add(String.format(Locale.US, "%.2f", b.avgResponseMs));
            jErr.add(String.format(Locale.US, "%.2f", b.errorPct));
            jTps.add(String.format(Locale.US, "%.2f", b.tps));
            jKb.add(String.format(Locale.US, "%.2f", b.kbps));
        }

        String labels = "[" + String.join(",", jLabels) + "]";
        String avgArr = "[" + String.join(",", jAvg) + "]";
        String errArr = "[" + String.join(",", jErr) + "]";
        String tpsArr = "[" + String.join(",", jTps) + "]";
        String kbArr = "[" + String.join(",", jKb) + "]";

        return new StringBuilder(2048)
                .append("<div class=\"charts-section\">\n")
                .append("  <h2>Performance Charts Over Time</h2>\n")
                .append("  <p class=\"charts-note\">Each point represents a 30-second interval.</p>\n")
                .append(chartBox("chartAvgRt", "Average Response Time Over Time (ms)"))
                .append(chartBox("chartErrPct", "Error Rate Over Time (%)"))
                .append(chartBox("chartTps", "Throughput Over Time (req/s)"))
                .append(chartBox("chartKb", "Received Bandwidth Over Time (KB/s)"))
                .append("</div>\n")
                .append("<script>\n(function() {\n")
                .append("  var labels = ").append(labels).append(";\n")
                .append(buildTimeSeriesChartFn())
                .append("  timeChart('chartAvgRt',  ").append(avgArr).append(", 'Avg Response Time', 'ms',    'rgba(49,130,206,1)');\n")
                .append("  timeChart('chartErrPct', ").append(errArr).append(", 'Error Rate',        '%',     'rgba(229,62,62,1)');\n")
                .append("  timeChart('chartTps',    ").append(tpsArr).append(", 'Throughput',        'req/s', 'rgba(72,187,120,1)');\n")
                .append("  timeChart('chartKb',     ").append(kbArr).append(", 'Bandwidth',         'KB/s',  'rgba(159,122,234,1)');\n")
                .append("})();\n</script>\n")
                .toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Output path
    // ─────────────────────────────────────────────────────────────

    private String markdownToHtml(String markdown) {
        String preprocessed = convertPipeTablesToHtml(markdown);
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(false).build();
        return renderer.render(parser.parse(preprocessed));
    }

    /**
     * Immutable value object grouping all report metadata.
     */
    public static final class RenderConfig {
        /**
         * Virtual user count label.
         */
        public final String users;
        /**
         * Test plan name.
         */
        public final String scenarioName;
        /**
         * Test plan description / comment.
         */
        public final String scenarioDesc;
        /**
         * First thread group name.
         */
        public final String threadGroupName;
        /**
         * Formatted test start time.
         */
        public final String startTime;
        /**
         * Formatted test end time.
         */
        public final String endTime;
        /**
         * Formatted test duration.
         */
        public final String duration;
        /**
         * Configured percentile (1–99).
         */
        public final int percentile;

        /**
         * Constructs a render configuration.
         *
         * @param users           virtual user count label (null → "")
         * @param scenarioName    test plan name (null → "")
         * @param scenarioDesc    test plan description (null → "")
         * @param threadGroupName first thread group name (null → "")
         * @param startTime       formatted start time (null → "")
         * @param endTime         formatted end time (null → "")
         * @param duration        formatted duration (null → "")
         * @param percentile      configured percentile (1–99)
         */
        public RenderConfig(String users, String scenarioName, String scenarioDesc,
                            String threadGroupName, String startTime, String endTime,
                            String duration, int percentile) {
            this.users = Objects.requireNonNullElse(users, "");
            this.scenarioName = Objects.requireNonNullElse(scenarioName, "");
            this.scenarioDesc = Objects.requireNonNullElse(scenarioDesc, "");
            this.threadGroupName = Objects.requireNonNullElse(threadGroupName, "");
            this.startTime = Objects.requireNonNullElse(startTime, "");
            this.endTime = Objects.requireNonNullElse(endTime, "");
            this.duration = Objects.requireNonNullElse(duration, "");
            this.percentile = percentile;
        }
    }
}
