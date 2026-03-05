package com.personal.jmeter;

import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Standalone preview of the Configurable Aggregate Report UI.
 * Run main() directly — no JMeter runtime needed.
 *
 * <p>Layout mirrors {@code ListenerGUI} exactly, with a manual file-browse
 * panel replacing AbstractVisualizer's built-in one.</p>
 */
public class UIPreview {

    // ── Fonts ────────────────────────────────────────────────────
    private static final Font FONT_HEADER = new Font("Calibri", Font.PLAIN, 13);
    private static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);
    private static final int PERCENTILE_COL_INDEX = 7;
    /**
     * Label used for the totals row — always pinned at bottom.
     */
    private static final String TOTAL_LABEL = "TOTAL";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("MM/dd/yy HH:mm:ss");
    // ── File section ─────────────────────────────────────────────
    private final JTextField fileNameField = new JTextField("", 40);
    // ── Filter settings ──────────────────────────────────────────
    private final JTextField startOffsetField = new JTextField("", 10);
    private final JTextField endOffsetField = new JTextField("", 10);
    private final JTextField percentileField = new JTextField("90", 10);
    // ── Results table ────────────────────────────────────────────
    // FIX 2: renamed "90th Percentile" → "90th Percentile(ms)"
    private final String[] COLUMN_NAMES = {
            "Transaction Name", "Transaction Count", "Transaction Passed",
            "Transaction Failed", "Avg Response Time(ms)", "Min Response Time(ms)",
            "Max Response Time(ms)", "90th Percentile(ms)", "Std. Dev.", "Error Rate", "TPS"
    };
    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable resultsTable = new JTable(tableModel);
    // ── Column visibility ────────────────────────────────────────
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[COLUMN_NAMES.length];
    private final TableColumn[] allTableColumns = new TableColumn[COLUMN_NAMES.length];
    // ── Bottom controls ──────────────────────────────────────────
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");
    // ── Time info fields (read-only) ─────────────────────────────
    private final JTextField startTimeField = new JTextField("", 20);
    private final JTextField endTimeField = new JTextField("", 20);
    private final JTextField durationField = new JTextField("", 20);
    // ── State ────────────────────────────────────────────────────
    private Map<String, SamplingStatCalculator> cachedResults = new HashMap<>();
    private String lastLoadedFilePath = null;
    /**
     * Current sort column index (-1 = no sort).
     */
    private int sortColumn = -1;
    /**
     * True = ascending, false = descending.
     */
    private boolean sortAscending = true;

    // ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Initialize JMeter properties from classpath resource (src/test/resources/)
        String propsPath = UIPreview.class.getClassLoader()
                .getResource("jmeter.properties").getFile();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(propsPath);
        org.apache.jmeter.util.JMeterUtils.initLocale();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            JFrame frame = new JFrame("Configurable Aggregate Report");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new UIPreview().buildUI());
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 500));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            System.out.println("[UI PREVIEW] Window opened successfully.");
        });
    }

    // ─────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        setupFieldListeners();

        // ── Top: title + name/comments + file + filters + column dropdown
        JPanel topWrapper = new JPanel(new BorderLayout(0, 0));
        JPanel titleAndFile = new JPanel(new BorderLayout(0, 0));
        titleAndFile.add(buildTitleBar(), BorderLayout.NORTH);
        titleAndFile.add(buildFilePanel(), BorderLayout.CENTER);
        topWrapper.add(titleAndFile, BorderLayout.NORTH);

        JPanel filterAndColumns = new JPanel();
        filterAndColumns.setLayout(new BoxLayout(filterAndColumns, BoxLayout.Y_AXIS));
        filterAndColumns.add(buildFilterPanel());
        filterAndColumns.add(buildTimeInfoPanel());
        topWrapper.add(filterAndColumns, BorderLayout.CENTER);

        // ── Centre: results table ────────────────────────────────
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20);

        // FIX 3: Replace TableRowSorter with manual sort that pins TOTAL at bottom
        resultsTable.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewCol = resultsTable.columnAtPoint(e.getPoint());
                if (viewCol < 0) return;
                int modelCol = resultsTable.convertColumnIndexToModel(viewCol);
                if (modelCol == sortColumn) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn = modelCol;
                    sortAscending = true;
                }
                sortAndRepopulateTable();
            }
        });

        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 200));

        // Store original columns after table is built
        storeOriginalColumns();

        // ── Bottom: save controls ────────────────────────────────
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> saveTableData());
        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);
        bottom.add(saveBtn);
        bottom.add(saveTableHeaderBox);

        root.add(topWrapper, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildTitleBar() {
        JPanel titleBar = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Configurable Aggregate Report");
        title.setFont(FONT_HEADER.deriveFont(Font.BOLD));
        titleBar.add(title, BorderLayout.WEST);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        nameRow.add(makeLabel("Name:"));
        nameRow.add(makeTextField("Configurable Aggregate Report", 28));
        nameRow.add(makeLabel("Comments:"));
        nameRow.add(makeTextField("", 28));
        titleBar.add(nameRow, BorderLayout.SOUTH);
        return titleBar;
    }

    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Write results to file / Read from file");
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(makeLabel("Filename"), c);

        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fileNameField.setFont(FONT_REGULAR);
        panel.add(fileNameField, c);

        JButton browseBtn = new JButton("Browse...");
        browseBtn.setFont(FONT_REGULAR);
        browseBtn.addActionListener(e -> browseJTL());
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(browseBtn, c);

        return panel;
    }

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Filter Settings");
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0.25;
        panel.add(makeLabel("Start Offset (Seconds)"), c);
        c.gridx = 1;
        c.weightx = 0.25;
        panel.add(makeLabel("End Offset (Seconds)"), c);
        c.gridx = 2;
        c.weightx = 0.25;
        panel.add(makeLabel("Percentile (%)"), c);
        c.gridx = 3;
        c.weightx = 0.25;
        panel.add(makeLabel("Visible Columns"), c);

        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        startOffsetField.setFont(FONT_REGULAR);
        panel.add(startOffsetField, c);
        c.gridx = 1;
        endOffsetField.setFont(FONT_REGULAR);
        panel.add(endOffsetField, c);
        c.gridx = 2;
        percentileField.setFont(FONT_REGULAR);
        panel.add(percentileField, c);

        c.gridx = 3;
        c.fill = GridBagConstraints.NONE;
        panel.add(buildColumnDropdown(), c);

        return panel;
    }

    private JButton buildColumnDropdown() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(COLUMN_NAMES[i], true);
            item.setFont(FONT_REGULAR);

            if (i == 0) {
                item.setEnabled(false);
            } else {
                final int colIndex = i;
                item.addActionListener(e -> toggleColumnVisibility(colIndex, item.isSelected()));
            }

            columnMenuItems[i] = item;
            popup.add(item);
        }

        JButton dropdownBtn = new JButton("Select Columns ▼");
        dropdownBtn.setFont(FONT_REGULAR);
        dropdownBtn.addActionListener(e -> popup.show(dropdownBtn, 0, dropdownBtn.getHeight()));
        return dropdownBtn;
    }

    // ── Helpers ───────────────────────────────────────────────────
    private JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_REGULAR);
        return l;
    }

    private JTextField makeTextField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setFont(FONT_REGULAR);
        return f;
    }

    private JPanel buildTimeInfoPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Test Time Info");
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0.33;
        panel.add(makeLabel("Start Date/Time"), c);
        c.gridx = 1;
        c.weightx = 0.33;
        panel.add(makeLabel("End Date/Time"), c);
        c.gridx = 2;
        c.weightx = 0.34;
        panel.add(makeLabel("Duration"), c);

        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        startTimeField.setFont(FONT_REGULAR);
        startTimeField.setEditable(false);
        startTimeField.setBackground(new Color(240, 240, 240));
        panel.add(startTimeField, c);

        c.gridx = 1;
        endTimeField.setFont(FONT_REGULAR);
        endTimeField.setEditable(false);
        endTimeField.setBackground(new Color(240, 240, 240));
        panel.add(endTimeField, c);

        c.gridx = 2;
        durationField.setFont(FONT_REGULAR);
        durationField.setEditable(false);
        durationField.setBackground(new Color(240, 240, 240));
        panel.add(durationField, c);

        return panel;
    }

    private void updateTimeInfo(JTLParser.ParseResult parseResult) {
        if (parseResult.startTimeMs > 0) {
            startTimeField.setText(TIME_FORMAT.format(new Date(parseResult.startTimeMs)));
        } else {
            startTimeField.setText("");
        }
        if (parseResult.endTimeMs > 0) {
            endTimeField.setText(TIME_FORMAT.format(new Date(parseResult.endTimeMs)));
        } else {
            endTimeField.setText("");
        }
        if (parseResult.durationMs > 0) {
            durationField.setText(formatDuration(parseResult.durationMs));
        } else {
            durationField.setText("");
        }
    }

    private String formatDuration(long durationMs) {
        long totalSec = durationMs / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    private void clearTimeInfo() {
        startTimeField.setText("");
        endTimeField.setText("");
        durationField.setText("");
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
                if (columnMenuItems[i].isSelected()) {
                    insertAt++;
                }
            }
            cm.addColumn(col);
            int currentPos = cm.getColumnCount() - 1;
            if (insertAt < currentPos) {
                cm.moveColumn(currentPos, insertAt);
            }
        } else {
            cm.removeColumn(col);
        }
    }

    private List<Integer> getVisibleColumnModelIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) {
                indices.add(i);
            }
        }
        return indices;
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners
    // ─────────────────────────────────────────────────────────────

    private void setupFieldListeners() {
        percentileField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> {
                    updatePercentileColumn();
                    refreshTableData();
                });

        SimpleDocListener offsetListener = this::reloadWithCurrentFilters;
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);
    }

    private void updatePercentileColumn() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        // FIX 2: append (ms) to match ListenerGUI
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue(p + "th Percentile(ms)");
        resultsTable.getTableHeader().repaint();
    }

    private void refreshTableData() {
        if (cachedResults.isEmpty()) return;
        try {
            int p = Integer.parseInt(percentileField.getText().trim());
            populateTableWithResults(cachedResults, p);
        } catch (NumberFormatException ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Table population
    // ─────────────────────────────────────────────────────────────

    /**
     * FIX 3: Separate TOTAL from data rows, sort only data rows, pin TOTAL last.
     * FIX 1: Use String.format("%.1f/sec") for TPS to guarantee leading zero.
     */
    private void populateTableWithResults(Map<String, SamplingStatCalculator> results,
                                          int percentile) {
        tableModel.setRowCount(0);
        DecimalFormat df0 = new DecimalFormat("#");
        DecimalFormat df1 = new DecimalFormat("0.0");
        DecimalFormat df2 = new DecimalFormat("0.00");
        double pFraction = percentile / 100.0;

        List<Object[]> dataRows = new ArrayList<>();
        Object[] totalRow = null;

        for (SamplingStatCalculator calc : results.values()) {
            if (calc.getCount() == 0) continue;

            long totalCount = calc.getCount();
            long failedCount = Math.round(calc.getErrorPercentage() * totalCount);
            long passedCount = totalCount - failedCount;

            Object[] row = new Object[]{
                    calc.getLabel(),
                    totalCount,
                    passedCount,
                    failedCount,
                    df0.format(calc.getMean()),
                    calc.getMin().intValue(),
                    calc.getMax().intValue(),
                    df0.format(calc.getPercentPoint(pFraction).doubleValue()),
                    df1.format(calc.getStandardDeviation()),
                    df2.format(calc.getErrorPercentage() * 100.0) + "%",
                    // FIX 1: %.1f always produces a leading zero (0.8 not .8)
                    String.format("%.1f/sec", calc.getRate())
            };

            if (TOTAL_LABEL.equals(calc.getLabel())) {
                totalRow = row;
            } else {
                dataRows.add(row);
            }
        }

        // FIX 3: Sort only data rows if a sort is active
        if (sortColumn >= 0 && sortColumn < COLUMN_NAMES.length) {
            final int col = sortColumn;
            final boolean asc = sortAscending;
            dataRows.sort((a, b) -> {
                Comparable<Object> va = toComparable(a[col]);
                Comparable<Object> vb = toComparable(b[col]);
                int cmp = va.compareTo(vb);
                return asc ? cmp : -cmp;
            });
        }

        for (Object[] row : dataRows) {
            tableModel.addRow(row);
        }
        // FIX 3: TOTAL always last, never sorted
        if (totalRow != null) {
            tableModel.addRow(totalRow);
        }
    }

    /**
     * Re-sort the current table data without re-parsing the file.
     */
    private void sortAndRepopulateTable() {
        if (cachedResults == null || cachedResults.isEmpty()) return;
        int p;
        try {
            p = Integer.parseInt(percentileField.getText().trim());
        } catch (NumberFormatException e) {
            p = 90;
        }
        populateTableWithResults(cachedResults, p);
    }

    /**
     * Convert a table cell value to a Comparable for sorting.
     * Strips suffixes like "%", "/sec" and parses as number where possible.
     */
    @SuppressWarnings("unchecked")
    private Comparable<Object> toComparable(Object val) {
        if (val == null) return (Comparable<Object>) (Comparable<?>) "";
        String s = val.toString();
        String numeric = s.replace("%", "").replace("/sec", "").trim();
        try {
            return (Comparable<Object>) (Comparable<?>) Double.parseDouble(numeric);
        } catch (NumberFormatException e) {
            return (Comparable<Object>) (Comparable<?>) s;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // JTL file loading
    // ─────────────────────────────────────────────────────────────

    private void browseJTL() {
        File startDir = new File(System.getProperty("user.dir"));
        String currentFile = fileNameField.getText().trim();
        if (!currentFile.isEmpty()) {
            File f = new File(currentFile);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                startDir = f.getParentFile();
            }
        }

        JFileChooser fc = new JFileChooser(startDir);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".jtl");
            }

            public String getDescription() {
                return "JTL Files (*.jtl)";
            }
        });
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            fileNameField.setText(f.getAbsolutePath());
            loadJTLFile(f.getAbsolutePath());
        }
    }

    private void loadJTLFile(String filePath) {
        lastLoadedFilePath = filePath;
        SwingUtilities.invokeLater(() -> {
            try {
                tableModel.setRowCount(0);
                JTLParser.FilterOptions opts = buildFilterOptions();
                JTLParser.ParseResult parseResult = new JTLParser().parse(filePath, opts);

                cachedResults = parseResult.results;
                populateTableWithResults(parseResult.results, opts.percentile);
                updateTimeInfo(parseResult);

                int txnCount = Math.max(0, parseResult.results.size() - 1);
                JOptionPane.showMessageDialog(null,
                        "Loaded " + txnCount + " transaction types from JTL file",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                JOptionPane.showMessageDialog(null,
                        "Error loading JTL file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void reloadWithCurrentFilters() {
        if (lastLoadedFilePath == null || lastLoadedFilePath.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                tableModel.setRowCount(0);
                JTLParser.FilterOptions opts = buildFilterOptions();
                JTLParser.ParseResult parseResult =
                        new JTLParser().parse(lastLoadedFilePath, opts);
                cachedResults = parseResult.results;
                populateTableWithResults(parseResult.results, opts.percentile);
                updateTimeInfo(parseResult);
            } catch (Exception e) {
                System.err.println("Error reloading: " + e.getMessage());
            }
        });
    }

    private JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        try {
            String s = startOffsetField.getText().trim();
            if (!s.isEmpty()) opts.startOffset = Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            opts.startOffset = 0;
        }
        try {
            String s = endOffsetField.getText().trim();
            if (!s.isEmpty()) opts.endOffset = Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            opts.endOffset = 0;
        }
        try {
            opts.percentile = Integer.parseInt(percentileField.getText().trim());
        } catch (NumberFormatException ignored) {
            opts.percentile = 90;
        }
        return opts;
    }

    // ─────────────────────────────────────────────────────────────
    // Save Table Data
    // ─────────────────────────────────────────────────────────────

    private void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(null,
                    "No data to save. Please load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }

            public String getDescription() {
                return "CSV Files (*.csv)";
            }
        });
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(null,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                JOptionPane.showMessageDialog(null,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveTableToCSV(File file) throws Exception {
        List<Integer> visibleCols = getVisibleColumnModelIndices();

        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            if (saveTableHeaderBox.isSelected()) {
                StringBuilder hdr = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) hdr.append(",");
                    // Use the live column header value — reflects dynamic updates
                    // such as the percentile label ("50th Percentile(ms)" vs "90th…")
                    hdr.append(allTableColumns[visibleCols.get(i)].getHeaderValue());
                }
                w.write(hdr.toString());
                w.newLine();
            }
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) line.append(",");
                    Object val = tableModel.getValueAt(row, visibleCols.get(i));
                    String cell = val != null ? val.toString() : "";
                    if (visibleCols.get(i) == 0) cell = escapeCSV(cell);
                    line.append(cell);
                }
                w.write(line.toString());
                w.newLine();
            }
        }
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ── Compact DocumentListener ─────────────────────────────────
    @FunctionalInterface
    private interface SimpleDocListener extends javax.swing.event.DocumentListener {
        void onUpdate();

        default void insertUpdate(javax.swing.event.DocumentEvent e) {
            onUpdate();
        }

        default void removeUpdate(javax.swing.event.DocumentEvent e) {
            onUpdate();
        }

        default void changedUpdate(javax.swing.event.DocumentEvent e) {
            onUpdate();
        }
    }
}