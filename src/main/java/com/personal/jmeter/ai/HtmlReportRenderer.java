package com.personal.jmeter.ai;

import com.personal.jmeter.parser.JTLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Converts an AI-generated Markdown report into a styled, standalone HTML file
 * and writes it next to the source JTL file.
 *
 * <p>Report layout:</p>
 * <ol>
 *   <li>Cover header — scenario metadata (via {@link HtmlPageBuilder})</li>
 *   <li>AI-generated analysis — Markdown → HTML (via {@link HtmlPageBuilder})</li>
 *   <li>Transaction Metrics table — built here</li>
 *   <li>Performance Charts — Chart.js (via {@link HtmlPageBuilder})</li>
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
     * Index {@value #PERCENTILE_COL_INDEX} (\"90th Pct (ms)\") matches the default percentile.
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

    /**
     * Minimum free disk space required before writing a report.
     * 10 MB is a conservative floor sized to accommodate typical JMeter report
     * output (50–200 KB) with ample headroom.
     */
    private static final long MIN_FREE_BYTES = 10L * 1024 * 1024; // 10 MB

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Renders the full AI report to an HTML file at the specified output path.
     * No Swing dialog is shown — suitable for headless / CLI invocation.
     *
     * @param markdownContent AI-generated report in Markdown; must not be null
     * @param outputPath      absolute path of the output HTML file; must not be null
     * @param config          scenario metadata; must not be null
     * @param tableRows       visible table rows from the plugin (TOTAL excluded)
     * @param timeBuckets     time buckets from the JTL parser
     * @return absolute path of the written HTML file (same as {@code outputPath})
     * @throws IOException if the file cannot be written
     */
    public String renderToFile(String markdownContent,
                               String outputPath,
                               RenderConfig config,
                               List<String[]> tableRows,
                               List<JTLParser.TimeBucket> timeBuckets) throws IOException {
        Objects.requireNonNull(markdownContent, "markdownContent must not be null");
        Objects.requireNonNull(outputPath,      "outputPath must not be null");
        Objects.requireNonNull(config,          "config must not be null");

        log.info("renderToFile: generating HTML report. outputPath={}", outputPath);

        String page = buildFullPage(markdownContent, config, tableRows, timeBuckets);
        writeReport(page, Path.of(outputPath));
        log.info("renderToFile: HTML report written. outputPath={}", outputPath);
        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────
    // Page assembly
    // ─────────────────────────────────────────────────────────────

    private String buildFullPage(String markdownContent, RenderConfig config,
                                 List<String[]> tableRows,
                                 List<JTLParser.TimeBucket> timeBuckets) {
        String htmlBody     = HtmlPageBuilder.markdownToHtml(markdownContent);
        String metricsTable = buildTransactionMetricsSection(
                tableRows, config.percentile,
                config.errorSlaThreshold, config.rtSlaThresholdMs, config.rtSlaMetric);
        String chartsBlock  = HtmlPageBuilder.buildChartsSection(timeBuckets);
        return HtmlPageBuilder.buildPage(htmlBody, metricsTable, chartsBlock, config);
    }

    // ─────────────────────────────────────────────────────────────
    // Transaction Metrics section (package-private for unit tests)
    // ─────────────────────────────────────────────────────────────

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
        return buildTransactionMetricsSection(rows, percentile,
                -1, -1, "pnn");
    }

    /**
     * Builds the Transaction Metrics HTML section with optional SLA status columns.
     *
     * <p>When SLA thresholds are configured ({@code > -1}), two extra columns are
     * appended — one for error-rate SLA status and one for response-time SLA status.
     * Each cell shows {@code PASS} (green) or {@code FAIL} (red). When a threshold
     * is not configured the cell shows {@code -}. All comparisons are performed here
     * in Java — no model arithmetic is involved.</p>
     *
     * @param rows               data rows to render (TOTAL excluded)
     * @param percentile         configured percentile — drives the Nth Pct column header
     * @param errorSlaThreshold  error-rate SLA threshold (%); -1 = not configured
     * @param rtSlaThresholdMs   response-time SLA threshold (ms); -1 = not configured
     * @param rtSlaMetric        {@code "avg"} = compare Avg RT; {@code "pnn"} = compare Nth Pct RT
     * @return HTML section string; empty string when rows is null or empty
     */
    String buildTransactionMetricsSection(List<String[]> rows, int percentile,
                                          double errorSlaThreshold, long rtSlaThresholdMs,
                                          String rtSlaMetric) {
        if (rows == null || rows.isEmpty()) return "";

        boolean hasErrorSla = errorSlaThreshold >= 0;
        boolean hasRtSla    = rtSlaThresholdMs >= 0;

        // ── Column headers ────────────────────────────────────────────────────
        String[] baseHeaders = buildTableHeaders(percentile);
        // Error SLA column header: "Error% SLA (≤N%)" or "Error% SLA"
        String errorSlaHeader = hasErrorSla
                ? "Error% SLA (\u2264" + formatThreshold(errorSlaThreshold) + "%)"
                : "Error% SLA";
        // RT SLA column header reflects the configured metric and threshold
        boolean useAvg = "avg".equalsIgnoreCase(rtSlaMetric);
        String rtSlaHeader = hasRtSla
                ? (useAvg ? "Avg" : "P" + percentile) + " RT SLA (\u2264" + rtSlaThresholdMs + " ms)"
                : (useAvg ? "Avg" : "P" + percentile) + " RT SLA";

        StringBuilder sb = new StringBuilder(512 + rows.size() * 200);
        sb.append("<div class=\"metrics-section\">\n")
                .append("  <h2>Transaction Metrics</h2>\n")
                .append("  <table>\n")
                .append("    <thead><tr>\n");

        for (String h : baseHeaders) {
            sb.append("      <th>").append(escapeHtml(h)).append("</th>\n");
        }
        sb.append("      <th>").append(escapeHtml(errorSlaHeader)).append("</th>\n");
        sb.append("      <th>").append(escapeHtml(rtSlaHeader)).append("</th>\n");
        sb.append("    </tr></thead>\n").append("    <tbody>\n");

        // ── Data rows ─────────────────────────────────────────────────────────
        // Column indices in buildRowAsStrings output:
        //   0=label, 1=count, 2=passed, 3=failed,
        //   4=avg(ms), 5=min(ms), 6=max(ms), 7=Nth pct(ms),
        //   8=stdDev, 9=errorRate%, 10=TPS
        final int ERROR_RATE_COL = 9;
        final int AVG_RT_COL     = 4;
        final int PNN_RT_COL     = 7;

        for (String[] row : rows) {
            sb.append("    <tr>\n");
            // Existing columns
            for (int i = 0; i < baseHeaders.length; i++) {
                String cell  = (row != null && i < row.length && row[i] != null) ? row[i] : "";
                String align = (i == 0) ? "" : " class=\"num\"";
                sb.append("      <td").append(align).append(">")
                        .append(escapeHtml(cell))
                        .append(TD_CLOSE).append("\n");
            }
            // Error SLA column
            sb.append("      ").append(buildSlaCell(
                    parseErrorRate(safeCell(row, ERROR_RATE_COL)),
                    errorSlaThreshold, hasErrorSla)).append("\n");
            // RT SLA column
            int rtCol = useAvg ? AVG_RT_COL : PNN_RT_COL;
            sb.append("      ").append(buildSlaCell(
                    parseMs(safeCell(row, rtCol)),
                    rtSlaThresholdMs, hasRtSla)).append("\n");

            sb.append("    </tr>\n");
        }
        sb.append("    </tbody>\n").append("  </table>\n").append("</div>\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // SLA cell helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@code <td>} cell showing PASS, FAIL, or - for an SLA check.
     *
     * @param observed    observed numeric value (error % or ms)
     * @param threshold   SLA threshold; ignored when {@code configured} is false
     * @param configured  whether the SLA is active
     * @return HTML {@code <td>} element string
     */
    private static String buildSlaCell(double observed, double threshold, boolean configured) {
        if (!configured) {
            return "<td class=\"sla-na\">-" + TD_CLOSE;
        }
        boolean breach = observed > threshold;
        return breach
                ? "<td class=\"sla-fail\">FAIL" + TD_CLOSE
                : "<td class=\"sla-pass\">PASS" + TD_CLOSE;
    }

    /** Returns the cell string at {@code index}, or "" if out of bounds or null. */
    private static String safeCell(String[] row, int index) {
        return (row != null && index < row.length && row[index] != null) ? row[index] : "";
    }

    /**
     * Parses an error-rate cell value (e.g. {@code "1.23%"}) to a double.
     * Returns 0.0 on parse failure.
     */
    static double parseErrorRate(String cell) {
        if (cell == null || cell.isBlank()) return 0.0;
        try {
            return Double.parseDouble(cell.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parses an integer-formatted ms cell (e.g. {@code "312"}) to a double.
     * Returns 0.0 on parse failure.
     */
    static double parseMs(String cell) {
        if (cell == null || cell.isBlank()) return 0.0;
        try {
            return Double.parseDouble(cell.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Formats a threshold value for the column header.
     * Returns an integer string when the value is a whole number (e.g. {@code "2"}),
     * otherwise one decimal place (e.g. {@code "2.5"}).
     */
    private static String formatThreshold(double value) {
        return (value == Math.floor(value)) ? String.valueOf((long) value)
                : String.format("%.1f", value);
    }

    // ─────────────────────────────────────────────────────────────
    // Utility (package-private for HtmlPageBuilder and tests)
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
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ─────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Writes {@code content} to {@code finalPath} using the mandatory atomic-write pattern:
     * disk-space check → write to sibling {@code .tmp} → ATOMIC_MOVE to final path
     * → guaranteed {@code .tmp} cleanup in {@code finally}.
     *
     * @param content   the HTML content to write; must not be null
     * @param finalPath the destination path (parent directory must be determinable)
     * @throws IOException if disk space is insufficient or the write/rename fails
     */
    private void writeReport(String content, Path finalPath) throws IOException {
        // Step 1: null-safe parent resolution
        Path parentDir = Objects.requireNonNull(
                finalPath.toAbsolutePath().getParent(),
                "finalPath must include a parent directory: " + finalPath);

        // Step 2: ensure parent directory exists, then check disk space.
        // Files.getFileStore() throws NoSuchFileException if parentDir does not exist —
        // createDirectories() is required BEFORE the space check, not after.
        Files.createDirectories(parentDir);
        long usable = Files.getFileStore(parentDir).getUsableSpace();
        if (usable < MIN_FREE_BYTES) {
            throw new IOException(String.format(
                    "Insufficient disk space — %.1f MB available, 10 MB required on %s",
                    usable / (1024.0 * 1024.0), parentDir));
        }

        // Step 3: write to sibling .tmp — same filesystem as finalPath, required for ATOMIC_MOVE
        Path tmpPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, content, StandardCharsets.UTF_8);

            // Step 4: atomic rename to final path
            try {
                Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // DESIGN: ATOMIC_MOVE can fail on non-NTFS filesystems (FAT32, exFAT),
                // network/CIFS shares, or some containerised volumes that do not support
                // atomic rename at the OS level. REPLACE_EXISTING is non-atomic but safe
                // for single-JVM use in these environments.
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Step 5: always delete .tmp — whether write succeeded, rename succeeded, or both failed.
            Files.deleteIfExists(tmpPath);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────

    private static String[] buildTableHeaders(int percentile) {
        String[] headers = TABLE_HEADERS.clone();
        headers[PERCENTILE_COL_INDEX] = percentile + "th Pct (ms)";
        return headers;
    }

    // ─────────────────────────────────────────────────────────────
    // RenderConfig value object
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable value object grouping all report metadata.
     */
    public static final class RenderConfig {
        /** Virtual user count label. */
        public final String users;
        /** Test plan name. */
        public final String scenarioName;
        /** Test plan description / comment. */
        public final String scenarioDesc;
        /** First thread group name. */
        public final String threadGroupName;
        /** Formatted test start time. */
        public final String startTime;
        /** Formatted test end time. */
        public final String endTime;
        /** Formatted test duration. */
        public final String duration;
        /** Configured percentile (1–99). */
        public final int    percentile;
        /** Display name of the AI provider used to generate the report (e.g. "Groq (Free)"). */
        public final String providerDisplayName;
        /** Error-rate SLA threshold (%); -1 means not configured. */
        public final double errorSlaThreshold;
        /** Response-time SLA threshold (ms); -1 means not configured. */
        public final long   rtSlaThresholdMs;
        /**
         * Which RT column the SLA applies to: {@code "avg"} for Avg RT,
         * {@code "pnn"} for Nth-percentile. Ignored when {@link #rtSlaThresholdMs} is -1.
         */
        public final String rtSlaMetric;

        /**
         * Constructs a render configuration.
         *
         * @param users               virtual user count label (null → "")
         * @param scenarioName        test plan name (null → "")
         * @param scenarioDesc        test plan description (null → "")
         * @param threadGroupName     first thread group name (null → "")
         * @param startTime           formatted start time (null → "")
         * @param endTime             formatted end time (null → "")
         * @param duration            formatted duration (null → "")
         * @param percentile          configured percentile (1–99)
         * @param providerDisplayName AI provider display name shown in the report footer (null → "")
         * @param errorSlaThreshold   error-rate SLA threshold (%); -1 = not configured
         * @param rtSlaThresholdMs    response-time SLA threshold (ms); -1 = not configured
         * @param rtSlaMetric         {@code "avg"} or {@code "pnn"} — which RT column to check
         */
        public RenderConfig(String users, String scenarioName, String scenarioDesc,
                            String threadGroupName, String startTime, String endTime,
                            String duration, int percentile, String providerDisplayName,
                            double errorSlaThreshold, long rtSlaThresholdMs,
                            String rtSlaMetric) {
            this.users               = Objects.requireNonNullElse(users, "");
            this.scenarioName        = Objects.requireNonNullElse(scenarioName, "");
            this.scenarioDesc        = Objects.requireNonNullElse(scenarioDesc, "");
            this.threadGroupName     = Objects.requireNonNullElse(threadGroupName, "");
            this.startTime           = Objects.requireNonNullElse(startTime, "");
            this.endTime             = Objects.requireNonNullElse(endTime, "");
            this.duration            = Objects.requireNonNullElse(duration, "");
            this.percentile          = percentile;
            this.providerDisplayName = Objects.requireNonNullElse(providerDisplayName, "");
            this.errorSlaThreshold   = errorSlaThreshold;
            this.rtSlaThresholdMs    = rtSlaThresholdMs;
            this.rtSlaMetric         = Objects.requireNonNullElse(rtSlaMetric, "pnn");
        }
    }
}