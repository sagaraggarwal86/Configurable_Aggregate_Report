package com.personal.jmeter.listener;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Reusable Swing panel for the Configurable Aggregate Report.
 *
 * // DESIGN: extends JPanel — required by Swing API; composition not possible
 * for a reusable embedded panel used by both ListenerGUI and UIPreview.
 *
 * <p>Responsibilities are delegated to specialist collaborators:</p>
 * <ul>
 *   <li>{@link ReportPanelBuilder} — Swing sub-panel and table construction</li>
 *   <li>{@link TablePopulator}     — table data, sorting, column visibility</li>
 *   <li>{@link CsvExporter}        — CSV save dialog and writing</li>
 *   <li>{@link AiReportLauncher}   — AI report generation workflow</li>
 * </ul>
 *
 * <h3>Public API for parent components</h3>
 * <ul>
 *   <li>{@link #loadJtlFile(String)} / {@link #loadJtlFile(String, boolean)}</li>
 *   <li>{@link #clearAll()}</li>
 *   <li>{@link #setSuppressReload(boolean)}</li>
 *   <li>{@link #setMetadataSupplier(Supplier)}</li>
 * </ul>
 */
public class AggregateReportPanel extends JPanel {

    // ── Shared fonts (also used by UIPreview and AiReportLauncher) ──
    /** Shared header font. */
    public static final Font FONT_HEADER  = new Font("Calibri", Font.PLAIN, 13);
    /** Shared body font. */
    public static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);

    // ── Column definitions ───────────────────────────────────────
    static final String[] ALL_COLUMNS = {
            "Transaction Name", "Transaction Count", "Transaction Passed",
            "Transaction Failed", "Avg Response Time(ms)", "Min Response Time(ms)",
            "Max Response Time(ms)", "90th Percentile(ms)", "Std. Dev.", "Error Rate", "TPS"
    };
    static final int    PERCENTILE_COL_INDEX = 7;
    static final String TOTAL_LABEL          = "TOTAL";

    private static final Logger log = LoggerFactory.getLogger(AggregateReportPanel.class);
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");

    // ── Filter fields ────────────────────────────────────────────
    final JTextField startOffsetField       = new JTextField("", 10);
    final JTextField endOffsetField         = new JTextField("", 10);
    final JTextField percentileField        = new JTextField("90", 10);
    final JTextField transactionSearchField = new JTextField("", 15);
    final JCheckBox  regexCheckBox          = new JCheckBox("RegEx");

    // ── Time-info fields ─────────────────────────────────────────
    final JTextField startTimeField = new JTextField("", 20);
    final JTextField endTimeField   = new JTextField("", 20);
    final JTextField durationField  = new JTextField("", 20);

    // ── Table components ─────────────────────────────────────────
    private final DefaultTableModel   tableModel = new DefaultTableModel(ALL_COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable              resultsTable    = new JTable(tableModel);
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[ALL_COLUMNS.length];
    private final TableColumn[]       allTableColumns = new TableColumn[ALL_COLUMNS.length];
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");

    // ── Background executor ───────────────────────────────────────
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-report-worker");
        t.setDaemon(true);
        return t;
    });

    // ── Collaborators ────────────────────────────────────────────
    private final TablePopulator    tablePopulator;
    private final CsvExporter       csvExporter;
    private final AiReportLauncher  aiReportLauncher;

    // ── State ────────────────────────────────────────────────────
    private Map<String, SamplingStatCalculator> cachedResults = Collections.emptyMap();
    private List<JTLParser.TimeBucket>          cachedBuckets = Collections.emptyList();
    private String  lastLoadedFilePath;
    private boolean suppressReload;
    private Supplier<ScenarioMetadata> metadataSupplier = ScenarioMetadata::empty;

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    /**
     * Constructs the panel and wires up all internal listeners.
     */
    public AggregateReportPanel() {
        super(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        tablePopulator   = new TablePopulator(tableModel, resultsTable,
                allTableColumns, columnMenuItems);
        csvExporter      = new CsvExporter(this, tableModel, allTableColumns,
                columnMenuItems, saveTableHeaderBox);
        aiReportLauncher = new AiReportLauncher(this, aiExecutor, new PanelDataProvider());

        ReportPanelBuilder builder = new ReportPanelBuilder(
                startOffsetField, endOffsetField, percentileField,
                transactionSearchField, regexCheckBox,
                startTimeField, endTimeField, durationField,
                resultsTable, columnMenuItems, allTableColumns,
                tablePopulator,
                viewCol -> tablePopulator.handleHeaderClick(viewCol,
                        () -> repopulate(readPercentile())));

        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(builder.buildFilterPanel());
        north.add(builder.buildTimeInfoPanel());
        add(north, BorderLayout.NORTH);
        add(builder.buildTableScrollPane(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        tablePopulator.storeOriginalColumns();
        setupFieldListeners();
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses and displays the given JTL file. Shows an error dialog on failure.
     *
     * @param filePath path to the JTL file
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath) { return loadJtlFile(filePath, false); }

    /**
     * Parses and displays the given JTL file.
     *
     * @param filePath          path to the JTL file
     * @param showSuccessDialog {@code true} to show a success count dialog
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath, boolean showSuccessDialog) {
        lastLoadedFilePath = filePath;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(filePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            repopulate(opts.percentile);
            updateTimeInfo(result);
            if (showSuccessDialog) {
                JOptionPane.showMessageDialog(this,
                        "Loaded " + Math.max(0, result.results.size() - 1)
                                + " transaction types from JTL file.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        } catch (IOException e) {
            log.error("loadJtlFile: parse failed. filePath={}, reason={}",
                    filePath, e.getMessage(), e);
            JOptionPane.showMessageDialog(this,
                    "Error loading JTL file:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Resets all fields and cached data to their initial state.
     */
    public void clearAll() {
        tableModel.setRowCount(0);
        cachedResults = Collections.emptyMap();
        cachedBuckets = Collections.emptyList();
        lastLoadedFilePath = null;
        suppressReload = false;
        startOffsetField.setText("");
        endOffsetField.setText("");
        percentileField.setText("90");
        transactionSearchField.setText("");
        regexCheckBox.setSelected(false);
        startTimeField.setText("");
        endTimeField.setText("");
        durationField.setText("");
    }

    /**
     * Suppresses automatic JTL reloads while filter fields are set programmatically.
     *
     * @param suppress {@code true} to suppress reloads
     */
    public void setSuppressReload(boolean suppress) { this.suppressReload = suppress; }

    /**
     * Sets the supplier invoked when the user clicks "Generate AI Report".
     *
     * @param supplier metadata supplier; {@code null} resets to the empty default
     */
    public void setMetadataSupplier(Supplier<ScenarioMetadata> supplier) {
        this.metadataSupplier = supplier != null ? supplier : ScenarioMetadata::empty;
    }

    /** @return start offset text */
    public String getStartOffset()        { return startOffsetField.getText().trim(); }
    /** @param v value; null treated as empty string */
    public void   setStartOffset(String v){ startOffsetField.setText(Objects.requireNonNullElse(v, "")); }
    /** @return end offset text */
    public String getEndOffset()          { return endOffsetField.getText().trim(); }
    /** @param v value; null treated as empty string */
    public void   setEndOffset(String v)  { endOffsetField.setText(Objects.requireNonNullElse(v, "")); }
    /** @return percentile text */
    public String getPercentileText()     { return percentileField.getText().trim(); }
    /** @param v value; null or blank resets to "90" */
    public void   setPercentile(String v) { percentileField.setText((v == null || v.isBlank()) ? "90" : v); }

    // ─────────────────────────────────────────────────────────────
    // Package-private API (used by collaborators and tests)
    // ─────────────────────────────────────────────────────────────

    JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset = parseIntField(startOffsetField, 0);
        opts.endOffset   = parseIntField(endOffsetField,   0);
        opts.percentile  = readPercentile();
        return opts;
    }

    List<String[]> getVisibleTableRows() { return tablePopulator.getVisibleRows(); }

    // ─────────────────────────────────────────────────────────────
    // Bottom panel
    // ─────────────────────────────────────────────────────────────

    private JPanel buildBottomPanel() {
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> csvExporter.saveTableData());
        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);
        JButton aiBtn = new JButton("Generate AI Report");
        aiBtn.setFont(FONT_REGULAR);
        aiBtn.setToolTipText(
                "Analyse the loaded JTL data with AI and generate an HTML performance report");
        aiBtn.addActionListener(e -> aiReportLauncher.launch(aiBtn));
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        panel.add(saveBtn);
        panel.add(saveTableHeaderBox);
        panel.add(aiBtn);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners
    // ─────────────────────────────────────────────────────────────

    private void setupFieldListeners() {
        percentileField.getDocument().addDocumentListener((SimpleDocListener) () -> {
            updatePercentileColumnHeader();
            reloadJtl();
        });
        SimpleDocListener offsetListener = this::reloadJtl;
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);
        transactionSearchField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> { if (!cachedResults.isEmpty()) repopulate(readPercentile()); });
        regexCheckBox.addActionListener(e -> { if (!cachedResults.isEmpty()) repopulate(readPercentile()); });
    }

    private void updatePercentileColumnHeader() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue(p + "th Percentile(ms)");
        resultsTable.getTableHeader().repaint();
    }

    private void reloadJtl() {
        if (suppressReload || lastLoadedFilePath == null
                || !new java.io.File(lastLoadedFilePath).exists()) return;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(lastLoadedFilePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            repopulate(opts.percentile);
            updateTimeInfo(result);
        } catch (IOException e) {
            log.error("reloadJtl: failed. filePath={}, reason={}",
                    lastLoadedFilePath, e.getMessage(), e);
        }
    }

    private void repopulate(int percentile) {
        tablePopulator.populate(cachedResults, percentile,
                transactionSearchField.getText().trim(), regexCheckBox.isSelected());
    }

    // ─────────────────────────────────────────────────────────────
    // Time info
    // ─────────────────────────────────────────────────────────────

    private void updateTimeInfo(JTLParser.ParseResult result) {
        startTimeField.setText(result.startTimeMs > 0 ? formatMs(result.startTimeMs) : "");
        endTimeField.setText(result.endTimeMs     > 0 ? formatMs(result.endTimeMs)   : "");
        durationField.setText(result.durationMs   > 0 ? formatDuration(result.durationMs) : "");
    }

    private static String formatMs(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DISPLAY_TIME_FORMAT);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%dh %dm %ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static int parseIntField(JTextField field, int fallback) {
        try {
            String t = field.getText().trim();
            return t.isEmpty() ? fallback : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int readPercentile() { return parseIntField(percentileField, 90); }

    // ─────────────────────────────────────────────────────────────
    // DataProvider — wires AiReportLauncher to panel state
    // ─────────────────────────────────────────────────────────────

    private final class PanelDataProvider implements AiReportLauncher.DataProvider {
        @Override public Map<String, SamplingStatCalculator> getCachedResults() { return cachedResults; }
        @Override public List<String[]> getVisibleTableRows()  { return tablePopulator.getVisibleRows(); }
        @Override public List<JTLParser.TimeBucket> getCachedBuckets() { return cachedBuckets; }
        @Override public String getLastLoadedFilePath()  { return lastLoadedFilePath; }
        @Override public ScenarioMetadata getMetadata()  { return metadataSupplier.get(); }
        @Override public int    getPercentile()          { return readPercentile(); }
        @Override public String getStartTime()           { return startTimeField.getText(); }
        @Override public String getEndTime()             { return endTimeField.getText(); }
        @Override public String getDuration()            { return durationField.getText(); }
    }
}
