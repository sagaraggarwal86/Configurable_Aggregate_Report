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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
     * Renders the full AI report to an HTML file on disk.
     *
     * <p>Uses an atomic write pattern: disk-space check → write to sibling {@code .tmp} →
     * atomic rename → guaranteed {@code .tmp} cleanup in {@code finally}.
     * This ensures no partial file is left on disk if the write or rename fails.</p>
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
        Objects.requireNonNull(jtlFilePath,     "jtlFilePath must not be null");
        Objects.requireNonNull(config,          "config must not be null");

        log.info("render: generating HTML report. jtlFile={}", jtlFilePath);

        String htmlBody     = HtmlPageBuilder.markdownToHtml(markdownContent);
        String metricsTable = buildTransactionMetricsSection(tableRows, config.percentile);
        String chartsBlock  = HtmlPageBuilder.buildChartsSection(timeBuckets);
        String page         = HtmlPageBuilder.buildPage(htmlBody, metricsTable, chartsBlock, config);

        String outPath = deriveOutputPath(jtlFilePath, config.scenarioName, config.threadGroupName);

        // Check if the derived output directory is writable; fall back to save dialog if not
        Path outDir = Path.of(outPath).toAbsolutePath().getParent();
        if (outDir != null && (!java.nio.file.Files.isWritable(outDir)
                || !java.nio.file.Files.isDirectory(outDir))) {
            log.warn("render: output directory not writable: {}. Prompting user.", outDir);
            String chosenPath = promptForOutputPath(outPath);
            if (chosenPath == null) {
                throw new IOException("Report save cancelled by user. "
                        + "The original location was not writable: " + outDir);
            }
            outPath = chosenPath;
        }

        writeReport(page, Path.of(outPath));
        log.info("render: HTML report written. outPath={}", outPath);
        return outPath;
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
        if (rows == null || rows.isEmpty()) return "";

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
                String cell  = (row != null && i < row.length && row[i] != null) ? row[i] : "";
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
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ─────────────────────────────────────────────────────────────
    // Save dialog fallback
    // ─────────────────────────────────────────────────────────────

    /**
     * Shows a save dialog on the EDT when the default output directory is not writable.
     * Called from the background AI-report thread; blocks until the user responds.
     *
     * @param suggestedPath the originally derived path (used as default filename)
     * @return the user-chosen absolute path, or {@code null} if the user cancelled
     * @throws IOException if the EDT invocation is interrupted
     */
    private String promptForOutputPath(String suggestedPath) throws IOException {
        final String[] result = {null};
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                fc.setDialogTitle("Save AI Report — original location not writable");
                fc.setSelectedFile(new java.io.File(suggestedPath).getName().isEmpty()
                        ? new java.io.File("AI_Generated_Report.html")
                        : new java.io.File(new java.io.File(suggestedPath).getName()));
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "HTML Files (*.html)", "html"));
                if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    java.io.File chosen = fc.getSelectedFile();
                    if (!chosen.getName().toLowerCase().endsWith(".html")) {
                        chosen = new java.io.File(chosen.getAbsolutePath() + ".html");
                    }
                    result[0] = chosen.getAbsolutePath();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Save dialog interrupted", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Save dialog failed: " + e.getCause().getMessage(), e);
        }
        return result[0];
    }

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

    private static String deriveOutputPath(String jtlFilePath, String scenarioName,
                                           String threadGroupName) {
        Path parentDir = Path.of(jtlFilePath).toAbsolutePath().getParent();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String planPart = sanitizeSegment(scenarioName);
        String tgPart   = sanitizeSegment(threadGroupName);

        StringBuilder name = new StringBuilder("AI_Generated_Report");
        if (!planPart.isEmpty()) name.append('_').append(planPart);
        name.append('_').append(timestamp).append(".html");

        return parentDir.resolve(name.toString()).toString();
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim()
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

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
            this.users           = Objects.requireNonNullElse(users, "");
            this.scenarioName    = Objects.requireNonNullElse(scenarioName, "");
            this.scenarioDesc    = Objects.requireNonNullElse(scenarioDesc, "");
            this.threadGroupName = Objects.requireNonNullElse(threadGroupName, "");
            this.startTime       = Objects.requireNonNullElse(startTime, "");
            this.endTime         = Objects.requireNonNullElse(endTime, "");
            this.duration        = Objects.requireNonNullElse(duration, "");
            this.percentile      = percentile;
        }
    }
}
