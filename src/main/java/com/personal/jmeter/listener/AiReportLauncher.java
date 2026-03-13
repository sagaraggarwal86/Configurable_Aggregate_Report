package com.personal.jmeter.listener;

import com.personal.jmeter.ai.AiProviderConfig;
import com.personal.jmeter.ai.AiProviderRegistry;
import com.personal.jmeter.ai.AiReportCoordinator;
import com.personal.jmeter.ai.AiReportService;
import com.personal.jmeter.ai.HtmlReportRenderer;
import com.personal.jmeter.ai.PromptBuilder;
import com.personal.jmeter.ai.PromptLoader;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Handles the AI report generation workflow initiated from the panel's button.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: provider validation, prompt
 * loading, context building, progress dialog management, and coordinator wiring.</p>
 *
 * <p>All data needed to build the report is supplied via constructor injection
 * and a {@link DataProvider} callback, keeping this class independently testable.</p>
 */
final class AiReportLauncher {

    private static final Logger log = LoggerFactory.getLogger(AiReportLauncher.class);

    private static final int PROGRESS_DIALOG_WIDTH = 340;
    private static final int PROGRESS_DIALOG_HEIGHT = 90;

    private final JComponent parent;
    private final ExecutorService executor;
    private final DataProvider dataProvider;

    /**
     * Callback interface that supplies live data from the parent panel.
     * Decouples {@link AiReportLauncher} from the panel's private fields.
     */
    interface DataProvider {
        /** Returns a snapshot of the cached aggregated results. */
        Map<String, SamplingStatCalculator> getCachedResults();

        /** Returns the visible (filtered + sorted) table rows, TOTAL excluded. */
        List<String[]> getVisibleTableRows();

        /** Returns the cached time buckets. */
        List<JTLParser.TimeBucket> getCachedBuckets();

        /** Returns the absolute path of the last loaded JTL file. */
        String getLastLoadedFilePath();

        /** Returns the current scenario metadata. */
        ScenarioMetadata getMetadata();

        /** Returns the currently configured percentile (1–99). */
        int getPercentile();

        /** Returns the formatted start time string. */
        String getStartTime();

        /** Returns the formatted end time string. */
        String getEndTime();

        /** Returns the formatted duration string. */
        String getDuration();

        /** Returns the provider selected in the UI dropdown, or {@code null} if none. */
        AiProviderConfig getSelectedProvider();

        /**
         * Returns the current SLA configuration snapshot from the UI fields.
         * Never null — returns a disabled SlaConfig if no thresholds are set.
         */
        com.personal.jmeter.listener.SlaConfig getSlaConfig();

        /**
         * Returns the top-5 error type summary from the last parsed JTL.
         * Empty list when no failures occurred.
         */
        List<Map<String, Object>> getErrorTypeSummary();

        /**
         * Returns the average Latency (TTFB) in ms from the last parsed JTL.
         * Zero when {@link #isLatencyPresent()} is false.
         */
        long getAvgLatencyMs();

        /**
         * Returns the average Connect time in ms from the last parsed JTL.
         * Zero when {@link #isLatencyPresent()} is false.
         */
        long getAvgConnectMs();

        /**
         * Returns {@code true} when the last parsed JTL contained at least one
         * non-zero Latency value.  Drives the direct vs inferred mode in the
         * Advanced Web Diagnostics section of the AI prompt.
         */
        boolean isLatencyPresent();
    }

    /**
     * Constructs the launcher.
     *
     * @param parent       the Swing parent for dialogs; must not be null
     * @param executor     background thread executor; must not be null
     * @param dataProvider live data callback; must not be null
     */
    AiReportLauncher(JComponent parent, ExecutorService executor, DataProvider dataProvider) {
        this.parent = parent;
        this.executor = executor;
        this.dataProvider = dataProvider;
    }

    /**
     * Validates provider selection and loads the prompt on the EDT, then immediately
     * disables the trigger button and shows the progress dialog before submitting work
     * to the background executor. API key validation and the live ping are performed
     * off the EDT so the UI remains responsive throughout.
     * Re-enables the button and disposes the dialog on both success and failure.
     *
     * @param triggerBtn the button that initiated the workflow (re-enabled on completion)
     */
    void launch(JButton triggerBtn) {
        if (dataProvider.getCachedResults().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No data available. Please load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AiProviderConfig providerConfig = dataProvider.getSelectedProvider();
        if (providerConfig == null) {
            JOptionPane.showMessageDialog(parent,
                    "<html>No AI provider configured.<br><br>"
                            + "Add your API key to:<br>"
                            + "&nbsp;&nbsp;<tt>$JMETER_HOME/bin/ai-reporter.properties</tt><br><br>"
                            + "Set at least one provider's <tt>api.key</tt> to enable this feature.</html>",
                    "No Provider Configured", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String systemPrompt = PromptLoader.load();
        if (systemPrompt == null) {
            JOptionPane.showMessageDialog(parent,
                    "<html><b>Could not load AI prompt.</b><br><br>"
                            + "The bundled prompt resource is missing or empty.<br>"
                            + "Please verify the plugin JAR is intact and reinstall if needed.</html>",
                    "Prompt Resource Missing", JOptionPane.ERROR_MESSAGE);
            return;
        }

        AiReportCoordinator.ReportContext context = buildReportContext(providerConfig.displayName);
        JDialog progressDialog = buildProgressDialog();
        JLabel progressLabel = extractProgressLabel(progressDialog);
        progressDialog.setVisible(true);
        triggerBtn.setEnabled(false);

        AiReportCoordinator coordinator = new AiReportCoordinator(
                new PromptBuilder(systemPrompt),
                new AiReportService(providerConfig),
                new HtmlReportRenderer(),
                executor);
        executor.submit(() -> {
            SwingUtilities.invokeLater(() -> progressLabel.setText("Validating API key..."));
            String pingError = AiProviderRegistry.validateAndPing(providerConfig);
            if (pingError != null) {
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    triggerBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(parent,
                            "<html><b>Cannot connect to " + providerConfig.displayName + ".</b><br><br>"
                                    + pingError.replace("\n", "<br>") + "</html>",
                            "Provider Validation Failed", JOptionPane.ERROR_MESSAGE);
                });
                return;
            }
            coordinator.start(context, progressDialog, progressLabel, triggerBtn);
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private AiReportCoordinator.ReportContext buildReportContext(String providerDisplayName) {
        final ScenarioMetadata metadata = dataProvider.getMetadata();
        final int percentile = dataProvider.getPercentile();
        final HtmlReportRenderer.RenderConfig config = new HtmlReportRenderer.RenderConfig(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                metadata.threadGroupName,
                dataProvider.getStartTime(), dataProvider.getEndTime(),
                dataProvider.getDuration(), percentile, providerDisplayName);

        final com.personal.jmeter.listener.SlaConfig sla = dataProvider.getSlaConfig();
        final String slaErrorPct = sla.isErrorPctEnabled()
                ? sla.errorPctThreshold + "%" : "Not configured";
        final String slaRtMs = sla.isRtEnabled()
                ? sla.rtThresholdMs + "ms" : "Not configured";
        final String slaRtMetric = sla.rtMetric == com.personal.jmeter.listener.SlaConfig.RtMetric.AVG
                ? "Avg (ms)" : "P" + percentile + " (ms)";

        return new AiReportCoordinator.ReportContext(
                Map.copyOf(dataProvider.getCachedResults()),
                dataProvider.getVisibleTableRows(),
                List.copyOf(dataProvider.getCachedBuckets()),
                config,
                dataProvider.getLastLoadedFilePath(),
                providerDisplayName,
                dataProvider.getDuration(),
                slaErrorPct,
                slaRtMs,
                slaRtMetric,
                List.copyOf(dataProvider.getErrorTypeSummary()),
                dataProvider.getAvgLatencyMs(),
                dataProvider.getAvgConnectMs(),
                dataProvider.isLatencyPresent());
    }

    /**
     * Returns the JMeter home directory, or {@code null} if not set.
     * Uses the {@code JMETER_HOME} system property or environment variable.
     * Package-accessible so {@link AggregateReportPanel} can use it for provider loading.
     */
    static java.io.File resolveJmeterHomeStatic() {
        return resolveJmeterHome();
    }

    /**
     * Returns the JMeter home directory, or {@code null} if not set.
     * Uses the {@code JMETER_HOME} system property or environment variable.
     */
    private static java.io.File resolveJmeterHome() {
        String home = System.getProperty("jmeter.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("JMETER_HOME");
        }
        if (home == null || home.isBlank()) return null;
        java.io.File f = new java.io.File(home);
        return f.isDirectory() ? f : null;
    }

    private JDialog buildProgressDialog() {
        Window parentWindow = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(parentWindow, "Generating AI Report",
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JLabel label = new JLabel("Initialising...");
        label.setFont(AggregateReportPanel.FONT_REGULAR);
        label.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        dialog.add(label, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(PROGRESS_DIALOG_WIDTH, PROGRESS_DIALOG_HEIGHT));
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    private JLabel extractProgressLabel(JDialog dialog) {
        return (JLabel) ((BorderLayout) dialog.getContentPane().getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
    }
}