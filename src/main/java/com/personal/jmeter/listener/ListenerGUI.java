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
import java.util.Set;

/**
 * JMeter listener plugin — Configurable Aggregate Report.
 *
 * <p>This class handles JMeter-specific integration only:
 * lifecycle callbacks ({@code configure}, {@code modifyTestElement}, {@code clearGui}),
 * hooking the built-in FilePanel (via {@link FilePanelCustomizer}), and reading
 * test-plan metadata for the AI report.
 * All shared UI and business logic lives in {@link AggregateReportPanel}.</p>
 */
public class ListenerGUI extends AbstractVisualizer {

    private static final Logger log = LoggerFactory.getLogger(ListenerGUI.class);

    private final AggregateReportPanel reportPanel = new AggregateReportPanel();

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    /**
     * Constructs the listener GUI and wires up JMeter integration.
     */
    public ListenerGUI() {
        super();
        initComponents();
        reportPanel.setMetadataSupplier(this::readTestPlanMetadata);
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────

    private void initComponents() {
        setLayout(new BorderLayout());

        // AbstractVisualizer's standard title / file-browse panel
        Container titlePanel = makeTitlePanel();
        add(titlePanel, BorderLayout.NORTH);

        // Build the exclusion set — all reportPanel fields that are editable
        // must be excluded from hookFilenameField so only the JTL path field is hooked.
        Set<JTextField> excluded = Set.of(
                reportPanel.startOffsetField, reportPanel.endOffsetField,
                reportPanel.percentileField,  reportPanel.startTimeField,
                reportPanel.endTimeField,     reportPanel.durationField,
                reportPanel.transactionSearchField);

        // Customise the built-in FilePanel: hide irrelevant controls,
        // override Browse to start in the current file's directory,
        // and monitor the filename field for auto-load.
        FilePanelCustomizer.hideFilePanelExtras(titlePanel);
        FilePanelCustomizer.overrideBrowseButton(titlePanel, getFile(), this::setFile, this);
        FilePanelCustomizer.hookFilenameField(titlePanel, excluded, this::checkAndLoadFile);

        add(reportPanel, BorderLayout.CENTER);
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
        el.setProperty(ListenerCollector.PROP_END_OFFSET,   reportPanel.getEndOffset());
        el.setProperty(ListenerCollector.PROP_PERCENTILE,   reportPanel.getPercentileText());
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
        // hookFilenameField triggers checkAndLoadFile() via invokeLater after configure() returns,
        // at which point all filter fields are already set.
    }

    @Override
    public void clearGui() {
        super.clearGui();
        reportPanel.clearAll();
    }

    /**
     * No-op: this plugin processes JTL files only — it does not capture live metrics.
     *
     * @param sample ignored
     */
    @Override
    public void add(SampleResult sample) {
        // Intentionally empty
    }

    // ─────────────────────────────────────────────────────────────
    // Test-plan metadata for AI report
    // ─────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearData() {
        reportPanel.clearAll();
    }

    /**
     * Reads scenario name, description, virtual-user count, and first thread-group
     * name from the live JMeter test-plan tree via {@link GuiPackage}.
     * Returns {@link ScenarioMetadata#empty()} on any failure.
     *
     * @return scenario metadata; never null
     */
    private ScenarioMetadata readTestPlanMetadata() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null) return ScenarioMetadata.empty();

            JMeterTreeNode root = (JMeterTreeNode) gp.getTreeModel().getRoot();
            Enumeration<?> children = root.children();
            while (children.hasMoreElements()) {
                JMeterTreeNode node = (JMeterTreeNode) children.nextElement();
                TestElement el = node.getTestElement();
                if (el instanceof TestPlan) {
                    String scenarioName = trimOrEmpty(el.getName());
                    String scenarioDesc = trimOrEmpty(el.getComment());
                    int    threadCount  = sumThreadCounts(node);
                    String threadGrpName = readFirstThreadGroupName(node);
                    String users = threadCount > 0 ? String.valueOf(threadCount) : "";
                    return new ScenarioMetadata(scenarioName, scenarioDesc, users, threadGrpName);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("readTestPlanMetadata: could not read test plan info. reason={}", ex.getMessage());
        }
        return ScenarioMetadata.empty();
    }

    private int sumThreadCounts(JMeterTreeNode planNode) {
        int total = 0;
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            if (child.getTestElement() instanceof AbstractThreadGroup atg) {
                total += atg.getNumThreads();
            }
        }
        return total;
    }

    private String readFirstThreadGroupName(JMeterTreeNode planNode) {
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            if (child.getTestElement() instanceof AbstractThreadGroup) {
                return trimOrEmpty(child.getTestElement().getName());
            }
        }
        return "";
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    // ─────────────────────────────────────────────────────────────
    // File loading (public — called from tests and JTL file monitoring)
    // ─────────────────────────────────────────────────────────────

    /**
     * Loads the given JTL file and populates the results table.
     *
     * @param filePath path to the JTL file
     * @return {@code true} on success, {@code false} on parse error
     */
    public boolean loadJTLFile(String filePath) {
        return reportPanel.loadJtlFile(filePath);
    }

    /**
     * Exposes the filter options for integration tests that need to verify
     * the parsed options without going through the full load flow.
     *
     * @return current filter options built from the UI fields
     */
    public JTLParser.FilterOptions buildFilterOptions() {
        return reportPanel.buildFilterOptions();
    }
}
