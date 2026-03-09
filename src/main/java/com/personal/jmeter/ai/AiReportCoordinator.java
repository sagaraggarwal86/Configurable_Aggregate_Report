package com.personal.jmeter.ai;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
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
 *   <li>Call the Groq API via {@link AiReportService}.</li>
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
        try {
            Desktop.getDesktop().browse(new File(htmlPath).toURI());
        } catch (IOException ex) {
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
            String prompt = buildPrompt(ctx);

            setProgress(progressLabel, "Calling Groq AI (this may take ~30 seconds)...");
            String markdown = aiService.generateReport(prompt);

            setProgress(progressLabel, "Rendering HTML report...");
            String htmlPath = renderReport(ctx, markdown);

            SwingUtilities.invokeLater(() -> onSuccess(htmlPath, progressDialog, triggerBtn));

        } catch (IOException ex) {
            log.error("executeReport: AI report generation failed. reason={}", ex.getMessage(), ex);
            SwingUtilities.invokeLater(() -> onFailure(ex, progressDialog, triggerBtn));
        }
    }

    private String buildPrompt(ReportContext ctx) {
        PromptRequest request = new PromptRequest(
                ctx.config.users, ctx.config.scenarioName, ctx.config.scenarioDesc,
                ctx.config.startTime, ctx.duration);
        return promptBuilder.build(ctx.results, ctx.config.percentile, request);
    }

    private String renderReport(ReportContext ctx, String markdown) throws IOException {
        return renderer.render(markdown, ctx.jtlPath, ctx.config, ctx.tableRows, ctx.timeBuckets);
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
        /**
         * Per-label aggregated statistics snapshot.
         */
        public final Map<String, SamplingStatCalculator> results;
        /**
         * Visible table rows as strings (TOTAL excluded).
         */
        public final List<String[]> tableRows;
        /**
         * Ordered list of 30-second time buckets.
         */
        public final List<JTLParser.TimeBucket> timeBuckets;
        /**
         * Render metadata for the HTML template.
         */
        public final HtmlReportRenderer.RenderConfig config;
        /**
         * Absolute path to the source JTL file.
         */
        public final String jtlPath;
        /**
         * Formatted test duration string.
         */
        public final String duration;

        /**
         * Constructs the report context.
         *
         * @param results     per-label aggregated statistics snapshot
         * @param tableRows   visible table rows as strings (TOTAL excluded)
         * @param timeBuckets ordered list of 30-second time buckets
         * @param config      render metadata for the HTML template
         * @param jtlPath     absolute path to the source JTL file
         * @param duration    formatted test duration string
         */
        public ReportContext(Map<String, SamplingStatCalculator> results,
                             List<String[]> tableRows,
                             List<JTLParser.TimeBucket> timeBuckets,
                             HtmlReportRenderer.RenderConfig config,
                             String jtlPath,
                             String duration) {
            this.results = Objects.requireNonNull(results, "results must not be null");
            this.tableRows = Objects.requireNonNull(tableRows, "tableRows must not be null");
            this.timeBuckets = Objects.requireNonNull(timeBuckets, "timeBuckets must not be null");
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.jtlPath = Objects.requireNonNull(jtlPath, "jtlPath must not be null");
            this.duration = duration != null ? duration : "";
        }
    }
}
