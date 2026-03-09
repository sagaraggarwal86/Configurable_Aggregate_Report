package com.personal.jmeter.listener;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.Enumeration;

/**
 * JMeter listener plugin — Configurable Aggregate Report.
 *
 * <p>This class handles JMeter-specific integration only:
 * lifecycle callbacks ({@code configure}, {@code modifyTestElement}, {@code clearGui}),
 * hooking the built-in FilePanel, and reading test-plan metadata for the AI report.
 * All shared UI and business logic lives in {@link AggregateReportPanel}.</p>
 */
public class ListenerGUI extends AbstractVisualizer {

    private static final Logger log = LoggerFactory.getLogger(ListenerGUI.class);

    private final AggregateReportPanel reportPanel = new AggregateReportPanel();

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    public ListenerGUI() {
        super();
        initComponents();
        reportPanel.setMetadataSupplier(this::readTestPlanMetadata);
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────

    private static File resolveStartDirectory(String currentFile) {
        if (currentFile != null && !currentFile.trim().isEmpty()) {
            File parent = new File(currentFile).getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.dir"));
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // AbstractVisualizer's standard title / file-browse panel
        Container titlePanel = makeTitlePanel();
        add(titlePanel, BorderLayout.NORTH);

        // Customise the built-in FilePanel: hide irrelevant controls,
        // override Browse to start in the current file's directory,
        // and monitor the filename field for auto-load.
        hideFilePanelExtras(titlePanel);
        overrideBrowseButton(titlePanel);
        hookFilenameField(titlePanel);

        add(reportPanel, BorderLayout.CENTER);
    }

    /**
     * Hides the "Log/Display Only", "Errors", "Successes", and "Configure"
     * controls that AbstractVisualizer's FilePanel adds but are irrelevant here.
     */
    private void hideFilePanelExtras(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JCheckBox cb) {
                String text = cb.getText();
                if (text != null && (text.contains("Log")
                        || text.contains("Errors")
                        || text.contains("Successes"))) {
                    cb.setVisible(false);
                }
            } else if (comp instanceof JButton btn
                    && btn.getText() != null
                    && btn.getText().contains("Configure")) {
                btn.setVisible(false);
            } else if (comp instanceof JLabel lbl
                    && lbl.getText() != null
                    && (lbl.getText().contains("Log") || lbl.getText().contains("Display"))) {
                lbl.setVisible(false);
            }
            if (comp instanceof Container c) {
                hideFilePanelExtras(c);
            }
        }
    }

    /**
     * Replaces the Browse button's action so the file chooser opens in the
     * directory of the currently selected file.
     */
    private void overrideBrowseButton(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn && btn.isVisible()
                    && btn.getText() != null && !btn.getText().contains("Configure")) {
                for (java.awt.event.ActionListener al : btn.getActionListeners()) {
                    btn.removeActionListener(al);
                }
                btn.addActionListener(e -> {
                    File startDir = resolveStartDirectory(getFile());
                    JFileChooser fc = new JFileChooser(startDir);
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                            "JTL Files (*.jtl)", "jtl"));
                    fc.setAcceptAllFileFilterUsed(true);
                    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        setFile(fc.getSelectedFile().getAbsolutePath());
                    }
                });
            }
            if (comp instanceof Container c) {
                overrideBrowseButton(c);
            }
        }
    }

    /**
     * Walks the component tree to find the filename {@link JTextField} inside
     * AbstractVisualizer's FilePanel and attaches a listener that triggers
     * auto-load when the path changes.
     */
    private void hookFilenameField(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JTextField tf && tf.isEditable()
                    && tf != reportPanel.startOffsetField
                    && tf != reportPanel.endOffsetField
                    && tf != reportPanel.percentileField
                    && tf != reportPanel.startTimeField
                    && tf != reportPanel.endTimeField
                    && tf != reportPanel.durationField
                    && tf != reportPanel.transactionSearchField) {
                tf.getDocument().addDocumentListener((AggregateReportPanel.SimpleDocListener) () ->
                        SwingUtilities.invokeLater(this::checkAndLoadFile));
            }
            if (comp instanceof Container c) {
                hookFilenameField(c);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AbstractVisualizer contract
    // ─────────────────────────────────────────────────────────────

    private void checkAndLoadFile() {
        String filename = getFile();
        if (filename != null && !filename.trim().isEmpty()
                && new File(filename).exists()) {
            reportPanel.loadJtlFile(filename);
        }
    }

    @Override
    public String getLabelResource() {
        return "configurable_aggregate_report";
    }

    @Override
    public String getStaticLabel() {
        return "Configurable Aggregate Report";
    }

    @Override
    public TestElement createTestElement() {
        ListenerCollector collector = new ListenerCollector();
        modifyTestElement(collector);
        return collector;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        super.modifyTestElement(el);
        el.setProperty(ListenerCollector.PROP_START_OFFSET, reportPanel.getStartOffset());
        el.setProperty(ListenerCollector.PROP_END_OFFSET, reportPanel.getEndOffset());
        el.setProperty(ListenerCollector.PROP_PERCENTILE, reportPanel.getPercentileText());
    }

    @Override
    public void configure(TestElement el) {
        reportPanel.setSuppressReload(true);
        try {
            super.configure(el);
            reportPanel.setStartOffset(
                    el.getPropertyAsString(ListenerCollector.PROP_START_OFFSET, ""));
            reportPanel.setEndOffset(
                    el.getPropertyAsString(ListenerCollector.PROP_END_OFFSET, ""));
            reportPanel.setPercentile(
                    el.getPropertyAsString(ListenerCollector.PROP_PERCENTILE, "90"));
        } finally {
            reportPanel.setSuppressReload(false);
        }
        // hookFilenameField will trigger checkAndLoadFile() via invokeLater
        // after configure() returns, at which point all filter fields are already set.
    }

    @Override
    public void clearGui() {
        super.clearGui();
        reportPanel.clearAll();
    }

    /**
     * No-op: this plugin processes JTL files only — it does not capture live metrics.
     */
    @Override
    public void add(SampleResult sample) {
        // Intentionally empty
    }

    // ─────────────────────────────────────────────────────────────
    // Test-plan metadata for AI report
    // ─────────────────────────────────────────────────────────────

    @Override
    public void clearData() {
        reportPanel.clearAll();
    }

    /**
     * Reads scenario name, description, virtual-user count, and first thread-group
     * name from the live JMeter test-plan tree via {@link GuiPackage}.
     * Returns {@link AggregateReportPanel.ScenarioMetadata#empty()} on any failure.
     */
    private AggregateReportPanel.ScenarioMetadata readTestPlanMetadata() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null) return AggregateReportPanel.ScenarioMetadata.empty();

            JMeterTreeNode root = (JMeterTreeNode) gp.getTreeModel().getRoot();
            Enumeration<?> children = root.children();
            while (children.hasMoreElements()) {
                JMeterTreeNode node = (JMeterTreeNode) children.nextElement();
                TestElement el = node.getTestElement();
                if (el instanceof TestPlan) {
                    String scenarioName = trimOrEmpty(el.getName());
                    String scenarioDesc = trimOrEmpty(el.getComment());
                    int threadCount = sumThreadCounts(node);
                    String threadGrpName = readFirstThreadGroupName(node);
                    String users = threadCount > 0 ? String.valueOf(threadCount) : "";
                    return new AggregateReportPanel.ScenarioMetadata(
                            scenarioName, scenarioDesc, users, threadGrpName);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("readTestPlanMetadata: could not read test plan info. reason={}", ex.getMessage());
        }
        return AggregateReportPanel.ScenarioMetadata.empty();
    }

    private int sumThreadCounts(JMeterTreeNode planNode) {
        int total = 0;
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            TestElement el = child.getTestElement();
            if (el instanceof AbstractThreadGroup atg) {
                total += atg.getNumThreads();
            }
        }
        return total;
    }

    private String readFirstThreadGroupName(JMeterTreeNode planNode) {
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            TestElement el = child.getTestElement();
            if (el instanceof AbstractThreadGroup) {
                return trimOrEmpty(el.getName());
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // File loading (public — called from tests and JTL file monitoring)
    // ─────────────────────────────────────────────────────────────

    /**
     * Loads the given JTL file and populates the results table.
     *
     * @return {@code true} on success, {@code false} on parse error
     */
    public boolean loadJTLFile(String filePath) {
        return reportPanel.loadJtlFile(filePath);
    }

    /**
     * Exposes the filter options for integration tests that need to verify
     * the parsed options without going through the full load flow.
     */
    public JTLParser.FilterOptions buildFilterOptions() {
        return reportPanel.buildFilterOptions();
    }
}
