package com.personal.jmeter.ai;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates the AI performance report workflow on a background thread.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Build the analysis prompt via {@link PromptBuilder}.</li>
 *   <li>Call the AI provider API via {@link AiReportService}.</li>
 *   <li>Render the HTML report via {@link HtmlReportRenderer}.</li>
 *   <li>Update the Swing progress dialog and re-enable the trigger button on the EDT.</li>
 * </ol>
 *
 * <p>All dependencies are constructor-injected, making this class independently
 * unit testable without a database, file-system, or live network connection.</p>
 */
public class AiReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AiReportCoordinator.class);

    private final PromptBuilder promptBuilder;
    private final AiReportService aiService;
    private final HtmlReportRenderer renderer;
    private final ExecutorService executor;

    /**
     * Constructs the coordinator with all required collaborators.
     *
     * @param promptBuilder the prompt assembly strategy; must not be null
     * @param aiService     the AI API client; must not be null
     * @param renderer      the HTML report renderer; must not be null
     * @param executor      the background executor for the AI call; must not be null
     */
    public AiReportCoordinator(PromptBuilder promptBuilder,
                               AiReportService aiService,
                               HtmlReportRenderer renderer,
                               ExecutorService executor) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
        this.aiService = Objects.requireNonNull(aiService, "aiService must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    private static void openInBrowser(String htmlPath) {
        if (!Desktop.isDesktopSupported()) {
            log.info("openInBrowser: Desktop API not supported on this platform — skipping.");
            return;
        }
        try {
            Desktop.getDesktop().browse(new File(htmlPath).toURI());
        } catch (IOException | UnsupportedOperationException ex) {
            log.warn("openInBrowser: could not open default browser. reason={}", ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private workflow
    // ─────────────────────────────────────────────────────────────

    private static void setProgress(JLabel label, String text) {
        SwingUtilities.invokeLater(() -> label.setText(text));
    }

    /**
     * Submits the AI report workflow to the background executor.
     * Progress is reported via {@code progressLabel}; {@code triggerBtn} is
     * re-enabled and {@code progressDialog} is disposed when the task completes.
     *
     * @param context        immutable snapshot of all data needed by the workflow
     * @param progressDialog modal-less progress dialog shown while the task runs
     * @param progressLabel  label inside the dialog updated with status messages
     * @param triggerBtn     the button that started the workflow (re-enabled on completion)
     */
    public void start(ReportContext context,
                      JDialog progressDialog,
                      JLabel progressLabel,
                      JButton triggerBtn) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(progressDialog, "progressDialog must not be null");
        Objects.requireNonNull(progressLabel, "progressLabel must not be null");
        Objects.requireNonNull(triggerBtn, "triggerBtn must not be null");

        executor.submit(() -> executeReport(context, progressDialog, progressLabel, triggerBtn));
    }

    private void executeReport(ReportContext ctx,
                               JDialog progressDialog,
                               JLabel progressLabel,
                               JButton triggerBtn) {
        try {
            setProgress(progressLabel, "Building analysis prompt...");
            PromptContent prompt = buildPrompt(ctx);

            setProgress(progressLabel, "Calling " + ctx.providerDisplayName + " (this may take ~30 seconds)...");
            String markdown = aiService.generateReport(prompt);

            setProgress(progressLabel, "Rendering HTML report...");
            String htmlPath = renderReport(ctx, markdown);

            SwingUtilities.invokeLater(() -> onSuccess(htmlPath, progressDialog, triggerBtn));

        } catch (IOException ex) {
            log.error("executeReport: AI report generation failed. reason={}", ex.getMessage(), ex);
            SwingUtilities.invokeLater(() -> onFailure(ex, progressDialog, triggerBtn));
        }
    }

    private PromptContent buildPrompt(ReportContext ctx) {
        PromptRequest request = new PromptRequest(
                ctx.config.users,
                ctx.config.scenarioName,
                ctx.config.scenarioDesc,
                ctx.config.startTime,
                ctx.config.endTime,
                ctx.duration,
                ctx.config.threadGroupName,
                ctx.config.percentile,
                ctx.slaErrorThresholdPct,
                ctx.slaRtThresholdMs,
                ctx.slaRtMetric);
        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
                ctx.avgLatencyMs, ctx.avgConnectMs, ctx.latencyPresent);
        return promptBuilder.build(ctx.results, ctx.config.percentile, request,
                ctx.errorTypeSummary, latency);
    }

    private String renderReport(ReportContext ctx, String markdown) throws IOException {
        String suggestedName = deriveSuggestedFileName(ctx.config.scenarioName);
        File startDir = Path.of(ctx.jtlPath).toAbsolutePath().getParent() != null
                ? Path.of(ctx.jtlPath).toAbsolutePath().getParent().toFile()
                : new File(System.getProperty("user.dir"));

        String outPath = promptForSavePath(suggestedName, startDir);
        if (outPath == null) {
            throw new IOException("Report save cancelled by user.");
        }

        return renderer.renderToFile(markdown, outPath, ctx.config, ctx.tableRows, ctx.timeBuckets);
    }

    // ─────────────────────────────────────────────────────────────
    // Save dialog and path helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Shows a save-file dialog on the EDT so the user chooses where to save the report.
     * Blocks the calling background thread until the user responds.
     *
     * @param suggestedName suggested filename (no directory component)
     * @param startDir      initial directory for the dialog
     * @return user-chosen absolute path, or {@code null} if the user cancelled
     * @throws IOException if the EDT invocation is interrupted
     */
    private static String promptForSavePath(String suggestedName, File startDir)
            throws IOException {
        final String[] result = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser fc = new JFileChooser(startDir);
                fc.setDialogTitle("Save AI Performance Report");
                fc.setSelectedFile(new File(suggestedName));
                fc.setFileFilter(new FileNameExtensionFilter("HTML Files (*.html)", "html"));
                if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    File chosen = fc.getSelectedFile();
                    if (!chosen.getName().toLowerCase().endsWith(".html")) {
                        chosen = new File(chosen.getAbsolutePath() + ".html");
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

    /**
     * Builds a suggested filename for the AI report (no directory component).
     *
     * @param scenarioName test plan name (may be null/blank)
     * @return suggested filename, e.g. {@code AI_Generated_Report_Checkout_20260311_143022.html}
     */
    private static String deriveSuggestedFileName(String scenarioName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String planPart  = sanitizeSegment(scenarioName);
        StringBuilder name = new StringBuilder("AI_Generated_Report");
        if (!planPart.isEmpty()) name.append('_').append(planPart);
        name.append('_').append(timestamp).append(".html");
        return name.toString();
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim()
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private void onSuccess(String htmlPath, JDialog progressDialog, JButton triggerBtn) {
        progressDialog.dispose();
        triggerBtn.setEnabled(true);
        openInBrowser(htmlPath);
        JOptionPane.showMessageDialog(triggerBtn.getParent(),
                "AI Report saved to:\n" + htmlPath
                        + "\n\nThe report has been opened in your browser.",
                "Report Generated", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onFailure(IOException ex, JDialog progressDialog, JButton triggerBtn) {
        progressDialog.dispose();
        triggerBtn.setEnabled(true);
        JOptionPane.showMessageDialog(triggerBtn.getParent(),
                "Report generation failed:\n\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ─────────────────────────────────────────────────────────────
    // Immutable context record (value object)
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all data the AI report workflow needs.
     * Built on the Swing EDT; handed to the background thread safely because
     * all fields are final and all collections are unmodifiable copies.
     */
    public static final class ReportContext {
        /** Per-label aggregated statistics snapshot. */
        public final Map<String, SamplingStatCalculator> results;
        /** Visible table rows as strings (TOTAL excluded). */
        public final List<String[]> tableRows;
        /** Ordered list of 30-second time buckets. */
        public final List<JTLParser.TimeBucket> timeBuckets;
        /** Render metadata for the HTML template. */
        public final HtmlReportRenderer.RenderConfig config;
        /** Absolute path to the source JTL file. */
        public final String jtlPath;
        /** Human-readable name of the selected AI provider (e.g. "Groq (Free)"). */
        public final String providerDisplayName;
        /** Formatted test duration string. */
        public final String duration;
        /** User-configured error % SLA; "Not configured" if disabled. */
        public final String slaErrorThresholdPct;
        /** User-configured response time SLA in ms; "Not configured" if disabled. */
        public final String slaRtThresholdMs;
        /** Response time metric the RT SLA applies to (e.g. "Avg (ms)", "P90 (ms)"). */
        public final String slaRtMetric;
        /**
         * Top-5 failure types by frequency from the full JTL.
         * Each entry: responseCode, responseMessage, count.
         * Empty list when no failures occurred.
         */
        public final List<Map<String, Object>> errorTypeSummary;
        /**
         * Average Latency (TTFB) in ms across all filtered samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgLatencyMs;
        /**
         * Average Connect time in ms across all filtered samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgConnectMs;
        /**
         * {@code true} when the parsed JTL contains at least one non-zero Latency value.
         * Passed to {@link PromptBuilder.LatencyContext} to select direct vs inferred
         * timing-decomposition mode in the AI prompt.
         */
        public final boolean latencyPresent;

        private static final String NOT_CONFIGURED = "Not configured";

        /**
         * Constructs the report context.
         *
         * @param results              per-label aggregated statistics snapshot
         * @param tableRows            visible table rows as strings (TOTAL excluded)
         * @param timeBuckets          ordered list of time buckets
         * @param config               render metadata for the HTML template
         * @param jtlPath              absolute path to the source JTL file
         * @param providerDisplayName  human-readable AI provider name; null → "AI Provider"
         * @param duration             formatted test duration string
         * @param slaErrorThresholdPct user error % SLA; null → "Not configured"
         * @param slaRtThresholdMs     user RT SLA in ms; null → "Not configured"
         * @param slaRtMetric          RT metric label; null → "Not configured"
         * @param errorTypeSummary     top-5 failure types; null → empty list
         * @param avgLatencyMs         average Latency ms (0 if latencyPresent is false)
         * @param avgConnectMs         average Connect ms (0 if latencyPresent is false)
         * @param latencyPresent       true iff ≥ 1 non-zero Latency value was parsed
         */
        public ReportContext(Map<String, SamplingStatCalculator> results,
                             List<String[]> tableRows,
                             List<JTLParser.TimeBucket> timeBuckets,
                             HtmlReportRenderer.RenderConfig config,
                             String jtlPath,
                             String providerDisplayName,
                             String duration,
                             String slaErrorThresholdPct,
                             String slaRtThresholdMs,
                             String slaRtMetric,
                             List<Map<String, Object>> errorTypeSummary,
                             long avgLatencyMs,
                             long avgConnectMs,
                             boolean latencyPresent) {
            this.results      = Objects.requireNonNull(results,      "results must not be null");
            this.tableRows    = Objects.requireNonNull(tableRows,    "tableRows must not be null");
            this.timeBuckets  = Objects.requireNonNull(timeBuckets,  "timeBuckets must not be null");
            this.config       = Objects.requireNonNull(config,       "config must not be null");
            this.jtlPath      = Objects.requireNonNull(jtlPath,      "jtlPath must not be null");
            this.providerDisplayName = providerDisplayName != null ? providerDisplayName : "AI Provider";
            this.duration     = duration != null ? duration : "";
            this.slaErrorThresholdPct = slaErrorThresholdPct != null ? slaErrorThresholdPct : NOT_CONFIGURED;
            this.slaRtThresholdMs     = slaRtThresholdMs     != null ? slaRtThresholdMs     : NOT_CONFIGURED;
            this.slaRtMetric          = slaRtMetric          != null ? slaRtMetric          : NOT_CONFIGURED;
            this.errorTypeSummary     = errorTypeSummary     != null
                    ? Collections.unmodifiableList(errorTypeSummary) : Collections.emptyList();
            this.avgLatencyMs   = avgLatencyMs;
            this.avgConnectMs   = avgConnectMs;
            this.latencyPresent = latencyPresent;
        }
    }
}