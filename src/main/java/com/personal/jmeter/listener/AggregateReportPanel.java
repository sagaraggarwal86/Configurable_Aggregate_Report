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
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
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
 * Reusable Swing panel containing all shared UI and logic for the
 * Configurable Aggregate Report: filter settings, results table, test-time info,
 * CSV export, and AI report generation.
 *
 * <p>Used by both {@link ListenerGUI} (JMeter plugin) and
 * {@code UIPreview} (standalone dev preview).</p>
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

    // ── Fonts ────────────────────────────────────────────────────
    public static final Font FONT_HEADER = new Font("Calibri", Font.PLAIN, 13);
    public static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);
    // ── Column definitions ───────────────────────────────────────
    static final String[] ALL_COLUMNS = {
            "Transaction Name", "Transaction Count", "Transaction Passed",
            "Transaction Failed", "Avg Response Time(ms)", "Min Response Time(ms)",
            "Max Response Time(ms)", "90th Percentile(ms)", "Std. Dev.", "Error Rate", "TPS"
    };
    static final int PERCENTILE_COL_INDEX = 7;
    static final String TOTAL_LABEL = "TOTAL";
    private static final Logger log = LoggerFactory.getLogger(AggregateReportPanel.class);
    // ── Layout constants ─────────────────────────────────────────
    private static final int TABLE_SCROLL_WIDTH = 900;
    private static final int TABLE_SCROLL_HEIGHT = 250;
    private static final int PROGRESS_DIALOG_WIDTH = 340;
    private static final int PROGRESS_DIALOG_HEIGHT = 90;
    private static final double FILTER_FIELD_WEIGHT = 0.25;
    private static final double TIME_FIELD_WEIGHT = 0.33;

    // ── DecimalFormat constants (EDT-only; static final is safe here) ──
    /**
     * DecimalFormat for integer-rounded values (response times).
     */
    private static final DecimalFormat FORMAT_INTEGER = new DecimalFormat("#");
    /**
     * DecimalFormat for one decimal place (std deviation).
     */
    private static final DecimalFormat FORMAT_ONE_DP = new DecimalFormat("0.0");
    /**
     * DecimalFormat for two decimal places (error rate).
     */
    private static final DecimalFormat FORMAT_TWO_DP = new DecimalFormat("0.00");

    /**
     * Thread-safe; safe to use as a static constant unlike {@code SimpleDateFormat}.
     */
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");

    // ── Filter fields ────────────────────────────────────────────
    final JTextField startOffsetField = new JTextField("", 10);
    final JTextField endOffsetField = new JTextField("", 10);
    final JTextField percentileField = new JTextField("90", 10);
    final JTextField transactionSearchField = new JTextField("", 15);
    // ── Time info ────────────────────────────────────────────────
    final JTextField startTimeField = new JTextField("", 20);
    final JTextField endTimeField = new JTextField("", 20);
    final JTextField durationField = new JTextField("", 20);
    private final JCheckBox regexCheckBox = new JCheckBox("RegEx");
    // ── Table ────────────────────────────────────────────────────
    private final DefaultTableModel tableModel = new DefaultTableModel(ALL_COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int col) {
            return false;
        }
    };
    private final JTable resultsTable = new JTable(tableModel);
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[ALL_COLUMNS.length];
    private final TableColumn[] allTableColumns = new TableColumn[ALL_COLUMNS.length];
    // ── Bottom controls ──────────────────────────────────────────
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-report-worker");
        t.setDaemon(true);
        return t;
    });
    // ── State ────────────────────────────────────────────────────
    private Map<String, SamplingStatCalculator> cachedResults = Collections.emptyMap();
    private List<JTLParser.TimeBucket> cachedBuckets = Collections.emptyList();
    private String lastLoadedFilePath;
    private int sortColumn = -1;
    private boolean sortAscending = true;
    private boolean suppressReload;
    /**
     * Called when the AI report button is clicked to retrieve scenario metadata.
     */
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

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.add(buildFilterPanel());
        northPanel.add(buildTimeInfoPanel());

        add(northPanel, BorderLayout.NORTH);
        add(buildTableScrollPane(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        storeOriginalColumns();
        setupFieldListeners();
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    private static String formatMs(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DISPLAY_TIME_FORMAT);
    }

    private static String formatDuration(long durationMs) {
        long totalSec = durationMs / 1000;
        return String.format("%dh %dm %ds",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    /**
     * RFC 4180 CSV escaping applied to every cell.
     */
    private static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static int parseIntField(JTextField field, int fallback) {
        try {
            String text = field.getText().trim();
            return text.isEmpty() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Compares two table cell values numerically when possible, lexicographically otherwise.
     */
    private static int compareTableValues(Object a, Object b) {
        double da = parseNumericCell(a);
        double db = parseNumericCell(b);
        if (!Double.isNaN(da) && !Double.isNaN(db)) {
            return Double.compare(da, db);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static double parseNumericCell(Object val) {
        if (val == null) return Double.NaN;
        String s = val.toString().replace("%", "").replace("/sec", "").trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static JPanel titledPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder(title);
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);
        return panel;
    }

    private static GridBagConstraints defaultConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.weightx = TIME_FIELD_WEIGHT;
        return c;
    }

    private static void addLabel(JPanel panel, String text, int gridx, GridBagConstraints c) {
        c.gridx = gridx;
        JLabel label = new JLabel(text);
        label.setFont(FONT_REGULAR);
        panel.add(label, c);
    }

    private static void addLabel(JPanel panel, String text, GridBagConstraints c) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_REGULAR);
        panel.add(label, c);
    }

    private static void addField(JPanel panel, JTextField field, int gridx, GridBagConstraints c) {
        c.gridx = gridx;
        field.setFont(FONT_REGULAR);
        panel.add(field, c);
    }

    // ─────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────

    private static void addReadOnlyField(JPanel panel, JTextField field, int gridx,
                                         GridBagConstraints c) {
        c.gridx = gridx;
        field.setFont(FONT_REGULAR);
        field.setEditable(false);
        field.setBackground(new Color(240, 240, 240));
        panel.add(field, c);
    }

    /**
     * Parses and displays the given JTL file. Shows an error dialog on failure.
     *
     * @param filePath path to the JTL file
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath) {
        return loadJtlFile(filePath, false);
    }

    /**
     * Parses and displays the given JTL file.
     *
     * @param filePath          path to the JTL file
     * @param showSuccessDialog {@code true} to show a "Loaded N transactions" dialog
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath, boolean showSuccessDialog) {
        lastLoadedFilePath = filePath;
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(filePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            populateTable(cachedResults, opts.percentile);
            updateTimeInfo(result);

            if (showSuccessDialog) {
                int txnCount = Math.max(0, result.results.size() - 1);
                JOptionPane.showMessageDialog(this,
                        "Loaded " + txnCount + " transaction types from JTL file.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        } catch (IOException e) {
            log.error("loadJtlFile: failed to parse JTL file. filePath={}, reason={}", filePath, e.getMessage(), e);
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
        startOffsetField.setText("");
        endOffsetField.setText("");
        percentileField.setText("90");
        transactionSearchField.setText("");
        regexCheckBox.setSelected(false);
        clearTimeInfo();
    }

    /**
     * Suppresses automatic JTL reloads while filter fields are being set programmatically.
     *
     * @param suppress {@code true} to suppress reloads
     */
    public void setSuppressReload(boolean suppress) {
        this.suppressReload = suppress;
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners
    // ─────────────────────────────────────────────────────────────

    /**
     * Sets the supplier invoked when the user clicks "Generate AI Report".
     *
     * @param supplier metadata supplier; {@code null} resets to the empty default
     */
    public void setMetadataSupplier(Supplier<ScenarioMetadata> supplier) {
        this.metadataSupplier = supplier != null ? supplier : ScenarioMetadata::empty;
    }

    /**
     * Returns the current start-offset text.
     *
     * @return start offset as entered by the user
     */
    public String getStartOffset() {
        return startOffsetField.getText().trim();
    }

    /**
     * Sets the start-offset field value.
     *
     * @param value value to set; null is treated as empty string
     */
    public void setStartOffset(String value) {
        startOffsetField.setText(Objects.requireNonNullElse(value, ""));
    }

    /**
     * Returns the current end-offset text.
     *
     * @return end offset as entered by the user
     */
    public String getEndOffset() {
        return endOffsetField.getText().trim();
    }

    // ─────────────────────────────────────────────────────────────
    // Table population
    // ─────────────────────────────────────────────────────────────

    /**
     * Sets the end-offset field value.
     *
     * @param value value to set; null is treated as empty string
     */
    public void setEndOffset(String value) {
        endOffsetField.setText(Objects.requireNonNullElse(value, ""));
    }

    /**
     * Returns the current percentile text.
     *
     * @return percentile as entered by the user
     */
    public String getPercentileText() {
        return percentileField.getText().trim();
    }

    /**
     * Sets the percentile field value.
     *
     * @param value value to set; null or blank resets to "90"
     */
    public void setPercentile(String value) {
        percentileField.setText((value == null || value.isBlank()) ? "90" : value);
    }

    // ─────────────────────────────────────────────────────────────
    // Column visibility
    // ─────────────────────────────────────────────────────────────

    private JPanel buildFilterPanel() {
        JPanel panel = titledPanel("Filter Settings");
        GridBagConstraints c = defaultConstraints();

        c.gridy = 0;
        addLabel(panel, "Start Offset (Seconds)", 0, c);
        addLabel(panel, "End Offset (Seconds)", 1, c);
        addLabel(panel, "Percentile (%)", 2, c);
        addLabel(panel, "Visible Columns", 3, c);

        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = FILTER_FIELD_WEIGHT;
        addField(panel, startOffsetField, 0, c);
        addField(panel, endOffsetField, 1, c);
        addField(panel, percentileField, 2, c);
        c.gridx = 3;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(buildColumnDropdown(), c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 4;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 1.0;
        addLabel(panel, "Transaction Search", c);
        c.gridwidth = 1;

        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        transactionSearchField.setFont(FONT_REGULAR);
        transactionSearchField.setToolTipText(
                "Filter table by transaction name. Supports plain text and RegEx.");
        panel.add(transactionSearchField, c);

        c.gridx = 3;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        regexCheckBox.setFont(FONT_REGULAR);
        regexCheckBox.setToolTipText("Treat search text as a regular expression");
        panel.add(regexCheckBox, c);

        return panel;
    }

    private JButton buildColumnDropdown() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < ALL_COLUMNS.length; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(ALL_COLUMNS[i], true);
            item.setFont(FONT_REGULAR);
            if (i == 0) {
                item.setEnabled(false);
            } else {
                final int col = i;
                item.addActionListener(e -> toggleColumnVisibility(col, item.isSelected()));
            }
            columnMenuItems[i] = item;
            popup.add(item);
        }
        JButton btn = new JButton("Select Columns \u25BC");
        btn.setFont(FONT_REGULAR);
        btn.addActionListener(e -> popup.show(btn, 0, btn.getHeight()));
        return btn;
    }

    private JPanel buildTimeInfoPanel() {
        JPanel panel = titledPanel("Test Time Info");
        GridBagConstraints c = defaultConstraints();
        c.gridy = 0;
        addLabel(panel, "Start Date/Time", 0, c);
        addLabel(panel, "End Date/Time", 1, c);
        addLabel(panel, "Duration", 2, c);

        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = TIME_FIELD_WEIGHT;
        addReadOnlyField(panel, startTimeField, 0, c);
        addReadOnlyField(panel, endTimeField, 1, c);
        addReadOnlyField(panel, durationField, 2, c);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────
    // Time info display
    // ─────────────────────────────────────────────────────────────

    private JScrollPane buildTableScrollPane() {
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20);

        resultsTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = resultsTable.columnAtPoint(e.getPoint());
                if (viewCol < 0) return;
                int modelCol = resultsTable.convertColumnIndexToModel(viewCol);
                if (modelCol == sortColumn) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = modelCol;
                    sortAscending = true;
                }
                repopulateSorted();
            }
        });

        JScrollPane scroll = new JScrollPane(resultsTable);
        scroll.setPreferredSize(new Dimension(TABLE_SCROLL_WIDTH, TABLE_SCROLL_HEIGHT));
        return scroll;
    }

    private JPanel buildBottomPanel() {
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> saveTableData());

        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);

        JButton aiBtn = new JButton("Generate AI Report");
        aiBtn.setFont(FONT_REGULAR);
        aiBtn.setToolTipText("Analyse the loaded JTL data with AI and generate an HTML performance report");
        aiBtn.addActionListener(e -> startAiReportGeneration(aiBtn));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        panel.add(saveBtn);
        panel.add(saveTableHeaderBox);
        panel.add(aiBtn);
        return panel;
    }

    private void setupFieldListeners() {
        percentileField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> {
                    updatePercentileColumnHeader();
                    reloadJtl();
                });
        SimpleDocListener offsetListener = this::reloadJtl;
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);

        transactionSearchField.getDocument().addDocumentListener(
                (SimpleDocListener) this::applySearchFilter);
        regexCheckBox.addActionListener(e -> applySearchFilter());
    }

    private void updatePercentileColumnHeader() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue(p + "th Percentile(ms)");
        resultsTable.getTableHeader().repaint();
    }

    // ─────────────────────────────────────────────────────────────
    // CSV export
    // ─────────────────────────────────────────────────────────────

    private void reloadJtl() {
        if (suppressReload
                || lastLoadedFilePath == null
                || !new File(lastLoadedFilePath).exists()) {
            return;
        }
        try {
            JTLParser.FilterOptions opts = buildFilterOptions();
            JTLParser.ParseResult result = new JTLParser().parse(lastLoadedFilePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            populateTable(cachedResults, opts.percentile);
            updateTimeInfo(result);
        } catch (IOException e) {
            log.error("reloadJtl: JTL reload failed. filePath={}, reason={}", lastLoadedFilePath, e.getMessage(), e);
        }
    }

    private void applySearchFilter() {
        if (cachedResults.isEmpty()) return;
        populateTable(cachedResults, readPercentile());
    }

    private void populateTable(Map<String, SamplingStatCalculator> results, int percentile) {
        tableModel.setRowCount(0);
        double pFraction = percentile / 100.0;
        String searchPat = transactionSearchField.getText().trim();
        boolean useRegex = regexCheckBox.isSelected();

        List<Object[]> dataRows = new ArrayList<>();
        Object[] totalRow = null;

        for (SamplingStatCalculator calc : results.values()) {
            if (calc.getCount() == 0) continue;
            String label = calc.getLabel();
            boolean isTotal = TOTAL_LABEL.equals(label);

            if (!isTotal && !TransactionFilter.matches(label, searchPat, useRegex)) continue;

            long total = calc.getCount();
            long failed = Math.round(calc.getErrorPercentage() * total);
            Object[] row = {
                    label,
                    total,
                    total - failed,
                    failed,
                    FORMAT_INTEGER.format(calc.getMean()),
                    calc.getMin().intValue(),
                    calc.getMax().intValue(),
                    FORMAT_INTEGER.format(calc.getPercentPoint(pFraction).doubleValue()),
                    FORMAT_ONE_DP.format(calc.getStandardDeviation()),
                    FORMAT_TWO_DP.format(calc.getErrorPercentage() * 100.0) + "%",
                    String.format("%.1f/sec", calc.getRate())
            };

            if (isTotal) {
                totalRow = row;
            } else {
                dataRows.add(row);
            }
        }

        if (sortColumn >= 0 && sortColumn < ALL_COLUMNS.length) {
            final int col = sortColumn;
            final boolean asc = sortAscending;
            dataRows.sort((a, b) -> {
                int cmp = compareTableValues(a[col], b[col]);
                return asc ? cmp : -cmp;
            });
        }

        dataRows.forEach(tableModel::addRow);
        if (totalRow != null) tableModel.addRow(totalRow);
    }

    // ─────────────────────────────────────────────────────────────
    // AI report generation
    // ─────────────────────────────────────────────────────────────

    /**
     * Re-sorts the currently cached data without re-parsing the JTL file.
     */
    private void repopulateSorted() {
        if (!cachedResults.isEmpty()) {
            populateTable(cachedResults, readPercentile());
        }
    }

    /**
     * Snapshots the currently visible (filtered + sorted) table rows as plain
     * {@code String[]} arrays, excluding the TOTAL row.
     * Must be called on the Swing EDT.
     *
     * @return unmodifiable list of visible data rows
     */
    List<String[]> getVisibleTableRows() {
        List<String[]> rows = new ArrayList<>(tableModel.getRowCount());
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            Object nameCell = tableModel.getValueAt(r, 0);
            if (TOTAL_LABEL.equals(nameCell != null ? nameCell.toString() : "")) continue;
            String[] cells = new String[ALL_COLUMNS.length];
            for (int c = 0; c < ALL_COLUMNS.length; c++) {
                Object v = tableModel.getValueAt(r, c);
                cells[c] = v != null ? v.toString() : "";
            }
            rows.add(cells);
        }
        return Collections.unmodifiableList(rows);
    }

    private void storeOriginalColumns() {
        TableColumnModel cm = resultsTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            allTableColumns[i] = cm.getColumn(i);
        }
    }

    private void toggleColumnVisibility(int colIndex, boolean visible) {
        TableColumnModel cm = resultsTable.getColumnModel();
        TableColumn col = allTableColumns[colIndex];
        if (visible) {
            int insertAt = 0;
            for (int i = 0; i < colIndex; i++) {
                if (columnMenuItems[i].isSelected()) insertAt++;
            }
            cm.addColumn(col);
            int currentPos = cm.getColumnCount() - 1;
            if (insertAt < currentPos) cm.moveColumn(currentPos, insertAt);
        } else {
            cm.removeColumn(col);
        }
    }

    private List<Integer> getVisibleColumnModelIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) indices.add(i);
        }
        return indices;
    }

    private void updateTimeInfo(JTLParser.ParseResult result) {
        startTimeField.setText(result.startTimeMs > 0 ? formatMs(result.startTimeMs) : "");
        endTimeField.setText(result.endTimeMs > 0 ? formatMs(result.endTimeMs) : "");
        durationField.setText(result.durationMs > 0 ? formatDuration(result.durationMs) : "");
    }

    // ─────────────────────────────────────────────────────────────
    // Filter options
    // ─────────────────────────────────────────────────────────────

    private void clearTimeInfo() {
        startTimeField.setText("");
        endTimeField.setText("");
        durationField.setText("");
    }

    private void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "No data to save. Load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "CSV Files (*.csv)", "csv"));
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(this,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("saveTableData: error saving CSV. filePath={}, reason={}", file.getAbsolutePath(), e.getMessage(), e);
                JOptionPane.showMessageDialog(this,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveTableToCSV(File file) throws IOException {
        List<Integer> visibleCols = getVisibleColumnModelIndices();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            if (saveTableHeaderBox.isSelected()) {
                StringBuilder header = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) header.append(',');
                    header.append(escapeCSV(
                            allTableColumns[visibleCols.get(i)].getHeaderValue().toString()));
                }
                writer.write(header.toString());
                writer.newLine();
            }
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) line.append(',');
                    Object val = tableModel.getValueAt(row, visibleCols.get(i));
                    line.append(escapeCSV(val != null ? val.toString() : ""));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sorting
    // ─────────────────────────────────────────────────────────────

    private void startAiReportGeneration(JButton triggerBtn) {
        if (cachedResults.isEmpty() || tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
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
                aiExecutor);
        coordinator.start(context, progressDialog, progressLabel, triggerBtn);
    }

    private String resolveApiKey() {
        String key = AiReportService.readApiKeyFromEnv();
        return (key != null) ? key : promptForApiKey();
    }

    // ─────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────

    private AiReportCoordinator.ReportContext buildReportContext() {
        final ScenarioMetadata metadata = metadataSupplier.get();
        final int percentile = readPercentile();
        final HtmlReportRenderer.RenderConfig config = new HtmlReportRenderer.RenderConfig(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                metadata.threadGroupName,
                startTimeField.getText(), endTimeField.getText(),
                durationField.getText(), percentile);

        return new AiReportCoordinator.ReportContext(
                Map.copyOf(cachedResults),
                getVisibleTableRows(),
                List.copyOf(cachedBuckets),
                config,
                lastLoadedFilePath,
                durationField.getText());
    }

    private JLabel extractProgressLabel(JDialog dialog) {
        return (JLabel) ((BorderLayout) dialog.getContentPane().getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
    }

    private String promptForApiKey() {
        JPasswordField keyField = new JPasswordField(40);
        keyField.setFont(FONT_REGULAR);
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

        int result = JOptionPane.showConfirmDialog(this, panel, "API Key Missing",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String key = new String(keyField.getPassword()).trim();
        if (key.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No API key entered.",
                    "API Key Missing", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return key;
    }

    private JDialog buildProgressDialog() {
        Window parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent, "Generating AI Report",
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JLabel label = new JLabel("Initialising...");
        label.setFont(FONT_REGULAR);
        label.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        dialog.add(label, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(PROGRESS_DIALOG_WIDTH, PROGRESS_DIALOG_HEIGHT));
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    /**
     * Builds filter options from the current field values.
     *
     * @return filter options reflecting the current UI state
     */
    JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset = parseIntField(startOffsetField, 0);
        opts.endOffset = parseIntField(endOffsetField, 0);
        opts.percentile = readPercentile();
        return opts;
    }

    private int readPercentile() {
        return parseIntField(percentileField, 90);
    }

    // ─────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────

    /**
     * Compact functional interface for Swing {@link DocumentListener} callbacks.
     * Avoids anonymous class boilerplate when all three events share the same action.
     */
    @FunctionalInterface
    interface SimpleDocListener extends DocumentListener {
        /**
         * Called when the document changes.
         */
        void onUpdate();

        @Override
        default void insertUpdate(DocumentEvent e) {
            onUpdate();
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            onUpdate();
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            onUpdate();
        }
    }

    /**
     * Scenario-level metadata passed to the AI report prompt.
     */
    public static final class ScenarioMetadata {
        /**
         * Test plan name.
         */
        public final String scenarioName;
        /**
         * Test plan description / comment.
         */
        public final String scenarioDesc;
        /**
         * Virtual user count label.
         */
        public final String users;
        /**
         * First thread group name.
         */
        public final String threadGroupName;

        /**
         * Constructs scenario metadata.
         *
         * @param scenarioName    test plan name (null → "")
         * @param scenarioDesc    test plan description (null → "")
         * @param users           virtual user count label (null → "")
         * @param threadGroupName first thread group name (null → "")
         */
        public ScenarioMetadata(String scenarioName, String scenarioDesc,
                                String users, String threadGroupName) {
            this.scenarioName = Objects.requireNonNullElse(scenarioName, "");
            this.scenarioDesc = Objects.requireNonNullElse(scenarioDesc, "");
            this.users = Objects.requireNonNullElse(users, "");
            this.threadGroupName = Objects.requireNonNullElse(threadGroupName, "");
        }

        /**
         * Returns an empty {@code ScenarioMetadata} instance.
         *
         * @return metadata with all fields empty
         */
        public static ScenarioMetadata empty() {
            return new ScenarioMetadata("", "", "", "");
        }
    }
}
