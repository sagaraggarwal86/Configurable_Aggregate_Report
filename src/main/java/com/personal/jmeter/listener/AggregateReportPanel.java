package com.personal.jmeter.listener;

import com.personal.jmeter.ai.AiProviderConfig;
import com.personal.jmeter.ai.AiProviderRegistry;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
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
 *   <li>{@link SlaConfig}          — immutable SLA threshold snapshot</li>
 *   <li>{@link SlaRowRenderer}     — cell-level SLA breach highlighting</li>
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
    /** Shared header font — bold 13pt, used for table column headers. */
    public static final Font FONT_HEADER  = new Font("Calibri", Font.BOLD,  13);
    /** Shared body font — plain 13pt, matches table column header size. */
    public static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 13);

    // ── Column definitions ───────────────────────────────────────
    static final String[] ALL_COLUMNS = {
            "Transaction Name", "Count", "Passed",
            "Failed", "Avg (ms)", "Min (ms)",
            "Max (ms)", "P90 (ms)", "Std. Dev.", "Error Rate", "TPS"
    };
    /** Model index of the configurable percentile column. */
    static final int    PERCENTILE_COL_INDEX  = 7;
    /** Model index of Avg (ms) — used by SlaRowRenderer. */
    static final int    AVG_COL_INDEX         = 4;
    /** Model index of Error Rate — used by SlaRowRenderer. */
    static final int    ERROR_RATE_COL_INDEX  = 9;
    /** Model index of Transaction Name — used by SlaRowRenderer. */
    static final int    NAME_COL_INDEX        = 0;
    static final String TOTAL_LABEL           = "TOTAL";

    private static final Logger log = LoggerFactory.getLogger(AggregateReportPanel.class);

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

    // ── SLA threshold fields (not persisted in .jmx) ─────────────
    private final JTextField        errorPctSlaField    = new JTextField("", 5);
    private final JComboBox<String> rtMetricCombo       = new JComboBox<>(new String[]{"Avg (ms)", "P90 (ms)"});
    private final JTextField        rtThresholdSlaField = new JTextField("", 6);

    // ── Chart interval field (not persisted in .jmx) ─────────────
    private final JTextField chartIntervalField = new JTextField("0", 4);

    // ── Table components ─────────────────────────────────────────
    private final DefaultTableModel   tableModel = new DefaultTableModel(ALL_COLUMNS, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable              resultsTable    = new JTable(tableModel);
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[ALL_COLUMNS.length];
    private final TableColumn[]       allTableColumns = new TableColumn[ALL_COLUMNS.length];

    /** Provider dropdown — populated lazily when opened; null item = no providers configured. */
    private final JComboBox<AiProviderConfig> providerCombo = new JComboBox<>();

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
    private Map<String, SamplingStatCalculator>  cachedResults          = Collections.emptyMap();
    private List<JTLParser.TimeBucket>           cachedBuckets          = Collections.emptyList();
    private List<Map<String, Object>>            cachedErrorTypeSummary = Collections.emptyList();
    private long    cachedAvgLatencyMs   = 0L;
    private long    cachedAvgConnectMs   = 0L;
    private boolean cachedLatencyPresent = false;
    private String  lastLoadedFilePath;
    private boolean suppressReload;
    private Supplier<ScenarioMetadata> metadataSupplier = ScenarioMetadata::empty;

    /**
     * Debounce timer for JTL reloads triggered by DocumentListener keystrokes.
     * Each keystroke restarts the 300 ms window; the parse only fires when the
     * user pauses typing.  This prevents a full two-pass file read on every
     * character in percentileField, startOffsetField, and endOffsetField.
     */
    private final javax.swing.Timer reloadDebounceTimer = new javax.swing.Timer(300, e -> reloadJtl());
    {
        reloadDebounceTimer.setRepeats(false);
    }

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
                columnMenuItems);
        aiReportLauncher = new AiReportLauncher(this, aiExecutor, new PanelDataProvider());

        ReportPanelBuilder builder = new ReportPanelBuilder(
                startOffsetField, endOffsetField, percentileField,
                transactionSearchField, regexCheckBox,
                startTimeField, endTimeField, durationField,
                errorPctSlaField, rtMetricCombo, rtThresholdSlaField,
                resultsTable, columnMenuItems, allTableColumns,
                tablePopulator,
                viewCol -> tablePopulator.handleHeaderClick(viewCol,
                        () -> repopulate(readPercentile())));

        JPanel north = new JPanel(new GridLayout(0, 1));
        north.add(builder.buildFilterPanel());
        north.add(builder.buildTimeInfoAndSlaRow());
        add(north, BorderLayout.NORTH);
        add(builder.buildTableScrollPane(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        tablePopulator.storeOriginalColumns();
        installSlaRenderer();
        setupFieldListeners();
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses and displays the given JTL file. Shows an error dialog on failure.
     * Resets all SLA fields and highlighting before loading new data.
     *
     * @param filePath path to the JTL file
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath) { return loadJtlFile(filePath, false); }

    /**
     * Parses and displays the given JTL file.
     * SLA fields and any breach highlighting are always reset before loading.
     *
     * @param filePath          path to the JTL file
     * @param showSuccessDialog {@code true} to show a success count dialog
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath, boolean showSuccessDialog) {
        lastLoadedFilePath = filePath;
        // Always reset SLA fields and highlighting before populating new data.
        // This ensures a fresh visual state regardless of what was loaded before.
        resetSlaFields();
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(filePath, opts);
            cachedResults          = result.results;
            cachedBuckets          = result.timeBuckets;
            cachedErrorTypeSummary = result.errorTypeSummary;
            cachedAvgLatencyMs     = result.avgLatencyMs;
            cachedAvgConnectMs     = result.avgConnectMs;
            cachedLatencyPresent   = result.latencyPresent;
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
                    "Failed to load JTL file:\n" + e.getMessage(),
                    "Load Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Resets all fields, SLA fields, and cached data to their initial state.
     * Clears any SLA breach highlighting.
     */
    public void clearAll() {
        tableModel.setRowCount(0);
        cachedResults          = Collections.emptyMap();
        cachedBuckets          = Collections.emptyList();
        cachedErrorTypeSummary = Collections.emptyList();
        cachedAvgLatencyMs     = 0L;
        cachedAvgConnectMs     = 0L;
        cachedLatencyPresent   = false;
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
        resetSlaFields();
    }

    /**
     * Resets SLA threshold inputs and chart interval to defaults, then clears
     * any breach highlighting. Called on every Load and on Clear.
     */
    private void resetSlaFields() {
        errorPctSlaField.setText("");
        rtThresholdSlaField.setText("");
        rtMetricCombo.setSelectedIndex(1); // default: P90 (ms)
        chartIntervalField.setText("0");
        resultsTable.repaint();
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
        opts.startOffset         = parseIntField(startOffsetField, 0);
        opts.endOffset           = parseIntField(endOffsetField,   0);
        opts.percentile          = readPercentile();
        opts.chartIntervalSeconds = parseIntField(chartIntervalField, 0);
        return opts;
    }

    List<String[]> getVisibleTableRows() { return tablePopulator.getVisibleRows(); }

    // ─────────────────────────────────────────────────────────────
    // SLA renderer installation
    // ─────────────────────────────────────────────────────────────

    /**
     * Installs {@link SlaRowRenderer} on the table as the default renderer.
     * Must be called after {@link TablePopulator#storeOriginalColumns()}.
     */
    private void installSlaRenderer() {
        SlaRowRenderer renderer = new SlaRowRenderer(
                this::buildSlaConfig,
                ERROR_RATE_COL_INDEX,
                AVG_COL_INDEX,
                PERCENTILE_COL_INDEX,
                NAME_COL_INDEX);
        resultsTable.setDefaultRenderer(Object.class, renderer);
    }

    /**
     * Builds a live {@link SlaConfig} snapshot from current field values.
     * Invalid or blank fields are treated as disabled thresholds — no exception
     * is thrown here; validation happens on focus-lost.
     *
     * @return current SLA config; never null
     */
    private SlaConfig buildSlaConfig() {
        String errorPctStr = errorPctSlaField.getText().trim();
        String rtStr       = rtThresholdSlaField.getText().trim();

        // Silently fall back to disabled for any unparseable values
        // (validation has already fired on focus-lost before this is called)
        try {
            SlaConfig.RtMetric metric = rtMetricCombo.getSelectedIndex() == 0
                    ? SlaConfig.RtMetric.AVG
                    : SlaConfig.RtMetric.PNN;
            return SlaConfig.from(
                    isValidErrorPct(errorPctStr)  ? errorPctStr : "",
                    isValidRtThreshold(rtStr)     ? rtStr       : "",
                    metric,
                    readPercentile());
        } catch (NumberFormatException e) {
            return SlaConfig.disabled(readPercentile());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Bottom panel
    // ─────────────────────────────────────────────────────────────

    private JPanel buildBottomPanel() {
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> csvExporter.saveTableData());

        // Provider dropdown — reload list every time the popup opens
        providerCombo.setFont(FONT_REGULAR);
        providerCombo.setToolTipText("AI provider to use for report generation");
        providerCombo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                refreshProviderCombo();
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        refreshProviderCombo(); // initial load

        JButton aiBtn = new JButton("Generate AI Report");
        aiBtn.setFont(FONT_REGULAR);
        aiBtn.setToolTipText(
                "Analyse the loaded JTL data with AI and generate an HTML performance report");
        aiBtn.addActionListener(e -> aiReportLauncher.launch(aiBtn));

        // Vertical divider between AI button and chart interval
        JSeparator divider = new JSeparator(SwingConstants.VERTICAL);
        divider.setPreferredSize(new Dimension(1, 20));

        JLabel chartLabel = new JLabel("Chart Interval (s, 0=auto):");
        chartLabel.setFont(FONT_REGULAR);
        chartIntervalField.setFont(FONT_REGULAR);
        chartIntervalField.setToolTipText("Time bucket size in seconds for charts. 0 = auto-calculate.");

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        panel.add(saveBtn);
        panel.add(providerCombo);
        panel.add(aiBtn);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(divider);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(chartLabel);
        panel.add(chartIntervalField);
        return panel;
    }

    /**
     * Reloads the provider list from {@code ai-reporter.properties} into the
     * {@link #providerCombo} dropdown.  If no providers are configured, inserts a
     * disabled placeholder item so the user sees clear feedback.
     */
    private void refreshProviderCombo() {
        java.io.File jmeterHome = AiReportLauncher.resolveJmeterHomeStatic();
        java.util.List<AiProviderConfig> providers =
                AiProviderRegistry.loadConfiguredProviders(jmeterHome);

        AiProviderConfig previousSelection =
                (AiProviderConfig) providerCombo.getSelectedItem();

        providerCombo.removeAllItems();

        if (providers.isEmpty()) {
            // Insert a non-selectable placeholder via a custom renderer approach:
            // add a null item and handle it in the renderer
            providerCombo.addItem(null);
            providerCombo.setEnabled(false);
            providerCombo.setToolTipText(
                    "No providers configured. Set api.key in $JMETER_HOME/bin/ai-reporter.properties");
        } else {
            providerCombo.setEnabled(true);
            providerCombo.setToolTipText("AI provider to use for report generation");
            for (AiProviderConfig p : providers) {
                providerCombo.addItem(p);
            }
            // Restore previous selection if still present
            if (previousSelection != null) {
                for (int i = 0; i < providerCombo.getItemCount(); i++) {
                    AiProviderConfig item = providerCombo.getItemAt(i);
                    if (item != null && item.providerKey.equals(previousSelection.providerKey)) {
                        providerCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners
    // ─────────────────────────────────────────────────────────────

    private void setupFieldListeners() {
        // ── Percentile: update column header + debounced reload + update combo label ──
        percentileField.getDocument().addDocumentListener((SimpleDocListener) () -> {
            updatePercentileColumnHeader();
            updateRtMetricComboLabel();
            reloadDebounceTimer.restart();
        });

        // ── Offset fields: debounced reload on change ──
        SimpleDocListener offsetListener = () -> reloadDebounceTimer.restart();
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);

        // ── Chart interval field: debounced reload on change ──
        chartIntervalField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> reloadDebounceTimer.restart());

        // ── Transaction search: repopulate on change ──
        transactionSearchField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> { if (!cachedResults.isEmpty()) repopulate(readPercentile()); });
        regexCheckBox.addActionListener(e -> { if (!cachedResults.isEmpty()) repopulate(readPercentile()); });

        // ── SLA fields: live repaint on change ──
        SimpleDocListener slaRepaintListener = () -> resultsTable.repaint();
        errorPctSlaField.getDocument().addDocumentListener(slaRepaintListener);
        rtThresholdSlaField.getDocument().addDocumentListener(slaRepaintListener);
        rtMetricCombo.addActionListener(e -> resultsTable.repaint());

        // ── Focus-lost validation: Filter Settings fields ──
        addPositiveIntFocusValidator(startOffsetField,
                "Start Offset must be a positive integer (or leave blank).");
        addPositiveIntFocusValidator(endOffsetField,
                "End Offset must be a positive integer (or leave blank).");
        addRangeFocusValidator(percentileField, 1, 99,
                "Percentile must be an integer between 1 and 99.");

        // ── Focus-lost validation: Chart interval field ──
        addRangeFocusValidator(chartIntervalField, 0, 3600,
                "Chart Interval must be an integer between 0 and 3600 seconds (0 = auto).");

        // ── Focus-lost validation: SLA fields ──
        addRangeFocusValidator(errorPctSlaField, 1, 99,
                "Error % threshold must be an integer between 1 and 99 (or leave blank).");
        addPositiveIntFocusValidator(rtThresholdSlaField,
                "Response Time threshold must be a positive integer in ms (or leave blank).");
    }

    // ─────────────────────────────────────────────────────────────
    // Validation helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Attaches a focus-lost validator that requires the field to be blank or
     * a positive integer (> 0). Shows an error dialog and clears only the
     * offending field on failure.
     */
    private void addPositiveIntFocusValidator(JTextField field, String message) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String val = field.getText().trim();
                if (val.isEmpty()) return;
                try {
                    int n = Integer.parseInt(val);
                    if (n <= 0) throw new NumberFormatException("not positive");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(AggregateReportPanel.this,
                            message, "Invalid Input", JOptionPane.ERROR_MESSAGE);
                    field.setText("");
                }
            }
        });
    }

    /**
     * Attaches a focus-lost validator that requires the field to be blank or
     * an integer within [{@code min}, {@code max}] inclusive. Shows an error
     * dialog and clears only the offending field on failure.
     */
    private void addRangeFocusValidator(JTextField field, int min, int max, String message) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String val = field.getText().trim();
                if (val.isEmpty()) return;
                try {
                    int n = Integer.parseInt(val);
                    if (n < min || n > max) throw new NumberFormatException("out of range");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(AggregateReportPanel.this,
                            message, "Invalid Input", JOptionPane.ERROR_MESSAGE);
                    field.setText("");
                }
            }
        });
    }

    // ── Inline validation predicates (used by buildSlaConfig) ──

    private static boolean isValidErrorPct(String s) {
        if (s == null || s.isBlank()) return false;
        try { int n = Integer.parseInt(s.trim()); return n >= 1 && n <= 99; }
        catch (NumberFormatException e) { return false; }
    }

    private static boolean isValidRtThreshold(String s) {
        if (s == null || s.isBlank()) return false;
        try { return Long.parseLong(s.trim()) > 0; }
        catch (NumberFormatException e) { return false; }
    }

    // ─────────────────────────────────────────────────────────────
    // Percentile / column header helpers
    // ─────────────────────────────────────────────────────────────

    private void updatePercentileColumnHeader() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue("P" + p + " (ms)");
        columnMenuItems[PERCENTILE_COL_INDEX].setText("P" + p + " (ms)");
        resultsTable.getTableHeader().repaint();
    }

    /**
     * Keeps the "Pnn (ms)" item in {@link #rtMetricCombo} in sync with the
     * current percentile field value, e.g. percentile=95 → "P95 (ms)".
     */
    private void updateRtMetricComboLabel() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        int selectedIdx = rtMetricCombo.getSelectedIndex();
        rtMetricCombo.removeItemAt(1);
        rtMetricCombo.insertItemAt("P" + p + " (ms)", 1);
        rtMetricCombo.setSelectedIndex(selectedIdx);
    }

    // ─────────────────────────────────────────────────────────────
    // JTL reload
    // ─────────────────────────────────────────────────────────────

    private void reloadJtl() {
        if (suppressReload || lastLoadedFilePath == null
                || !new java.io.File(lastLoadedFilePath).exists()) return;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(lastLoadedFilePath, opts);
            cachedResults          = result.results;
            cachedBuckets          = result.timeBuckets;
            cachedErrorTypeSummary = result.errorTypeSummary;
            cachedAvgLatencyMs     = result.avgLatencyMs;
            cachedAvgConnectMs     = result.avgConnectMs;
            cachedLatencyPresent   = result.latencyPresent;
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
        startTimeField.setText(result.formattedStartTime());
        endTimeField.setText(result.formattedEndTime());
        durationField.setText(result.formattedDuration());
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
        @Override public AiProviderConfig getSelectedProvider() {
            return (AiProviderConfig) providerCombo.getSelectedItem();
        }
        @Override public SlaConfig getSlaConfig() { return buildSlaConfig(); }
        @Override public List<Map<String, Object>> getErrorTypeSummary() { return cachedErrorTypeSummary; }
        @Override public long    getAvgLatencyMs()  { return cachedAvgLatencyMs;   }
        @Override public long    getAvgConnectMs()  { return cachedAvgConnectMs;   }
        @Override public boolean isLatencyPresent() { return cachedLatencyPresent; }
    }
}