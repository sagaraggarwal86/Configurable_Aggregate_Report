package com.personal.jmeter.listener;

import com.personal.jmeter.ai.AiReportCoordinator;
import com.personal.jmeter.ai.AiReportService;
import com.personal.jmeter.ai.HtmlReportRenderer;
import com.personal.jmeter.ai.PromptBuilder;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Handles the AI report generation workflow initiated from the panel's button.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: API key resolution, context
 * building, progress dialog management, and coordinator wiring.</p>
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
        /**
         * Returns a snapshot of the cached aggregated results.
         */
        Map<String, SamplingStatCalculator> getCachedResults();

        /**
         * Returns the visible (filtered + sorted) table rows, TOTAL excluded.
         */
        List<String[]> getVisibleTableRows();

        /**
         * Returns the cached time buckets.
         */
        List<JTLParser.TimeBucket> getCachedBuckets();

        /**
         * Returns the absolute path of the last loaded JTL file.
         */
        String getLastLoadedFilePath();

        /**
         * Returns the current scenario metadata.
         */
        ScenarioMetadata getMetadata();

        /**
         * Returns the currently configured percentile (1–99).
         */
        int getPercentile();

        /**
         * Returns the formatted start time string.
         */
        String getStartTime();

        /**
         * Returns the formatted end time string.
         */
        String getEndTime();

        /**
         * Returns the formatted duration string.
         */
        String getDuration();
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
     * Validates data availability, resolves the API key, builds the report context,
     * shows the progress dialog, and submits the workflow to the background executor.
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

        String apiKey = resolveApiKey();
        if (apiKey == null) return;

        AiReportCoordinator.ReportContext context = buildReportContext();
        JDialog progressDialog = buildProgressDialog();
        JLabel progressLabel = extractProgressLabel(progressDialog);
        progressDialog.setVisible(true);
        triggerBtn.setEnabled(false);

        AiReportCoordinator coordinator = new AiReportCoordinator(
                new PromptBuilder(),
                new AiReportService(apiKey),
                new HtmlReportRenderer(),
                executor);
        coordinator.start(context, progressDialog, progressLabel, triggerBtn);
    }

    private String resolveApiKey() {
        String key = AiReportService.readApiKeyFromEnv();
        return (key != null) ? key : promptForApiKey();
    }

    private AiReportCoordinator.ReportContext buildReportContext() {
        final ScenarioMetadata metadata = dataProvider.getMetadata();
        final int percentile = dataProvider.getPercentile();
        final HtmlReportRenderer.RenderConfig config = new HtmlReportRenderer.RenderConfig(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                metadata.threadGroupName,
                dataProvider.getStartTime(), dataProvider.getEndTime(),
                dataProvider.getDuration(), percentile);

        return new AiReportCoordinator.ReportContext(
                Map.copyOf(dataProvider.getCachedResults()),
                dataProvider.getVisibleTableRows(),
                List.copyOf(dataProvider.getCachedBuckets()),
                config,
                dataProvider.getLastLoadedFilePath(),
                dataProvider.getDuration());
    }

    private String promptForApiKey() {
        JPasswordField keyField = new JPasswordField(40);
        keyField.setFont(AggregateReportPanel.FONT_REGULAR);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("<html><b>Environment variable '"
                        + AiReportService.ENV_VAR_NAME + "' not found.</b><br><br>"
                        + "Set it permanently:<br>"
                        + "&nbsp;&nbsp;Windows: <tt>setx " + AiReportService.ENV_VAR_NAME
                        + " \"your-key\"</tt><br>"
                        + "&nbsp;&nbsp;Linux/Mac: add <tt>export " + AiReportService.ENV_VAR_NAME
                        + "=your-key</tt> to ~/.bashrc<br><br>"
                        + "Or enter your Groq API key below for this session only:</html>"),
                BorderLayout.NORTH);
        panel.add(keyField, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(parent, panel, "API Key Missing",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String key = new String(keyField.getPassword()).trim();
        if (key.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No API key entered.",
                    "API Key Missing", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return key;
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
