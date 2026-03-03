package com.sagar.jmeter;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.parser.JTLParser;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone preview of the Advanced Aggregate Report UI.
 * Run main() directly — no JMeter runtime needed.
 */
public class UIPreview {

    // File section
    private final JTextField fileNameField     = new JTextField("", 40);

    // Filter settings
    private final JTextField startOffsetField   = new JTextField("", 10);
    private final JTextField endOffsetField     = new JTextField("", 10);
    private final JTextField includeLabelsField = new JTextField("", 20);
    private final JTextField excludeLabelsField = new JTextField("", 20);
    private final JCheckBox  regExpBox          = new JCheckBox();
    private final JTextField percentileField    = new JTextField("90", 10);

    // Results table
    private final String[] COLUMN_NAMES = {
        "Transaction Name", "Transaction Count", "Average", "Min", "Max",
        "90% Line", "Std. Dev.", "Error %", "Throughput"
    };
    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable resultsTable = new JTable(tableModel);

    // Bottom controls
    private final JCheckBox includeGroupNameBox = new JCheckBox("Include group name in label?");
    private final JCheckBox saveTableHeaderBox  = new JCheckBox("Save Table Header");

    // Cache for loaded results to support dynamic percentile updates
    private Map<String, AggregateResult> cachedResults = new HashMap<>();

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Setup listener for percentile field to update table column header
        percentileField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { 
                updatePercentileColumn();
                refreshTableData();
            }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { 
                updatePercentileColumn();
                refreshTableData();
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { 
                updatePercentileColumn();
                refreshTableData();
            }
        });

        // ── Title bar ──────────────────────────────────────────────────────
        JPanel titleBar = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Advanced Aggregate Report");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        titleBar.add(title, BorderLayout.WEST);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        nameRow.add(new JLabel("Name:"));
        nameRow.add(new JTextField("Advanced Aggregate Report", 28));
        nameRow.add(new JLabel("Comments:"));
        nameRow.add(new JTextField("", 28));
        titleBar.add(nameRow, BorderLayout.SOUTH);

        // ── File panel ─────────────────────────────────────────────────────
        JPanel filePanel = new JPanel(new GridBagLayout());
        filePanel.setBorder(new TitledBorder("Write results to file / Read from file"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        filePanel.add(new JLabel("Filename"), c);

        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        filePanel.add(fileNameField, c);

        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
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
        });
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        filePanel.add(browseBtn, c);

        // ── Filter settings panel ──────────────────────────────────────────
        JPanel filterPanel = new JPanel(new GridBagLayout());
        filterPanel.setBorder(new TitledBorder("Filter settings"));
        GridBagConstraints fc2 = new GridBagConstraints();
        fc2.insets = new Insets(4, 6, 4, 6);
        fc2.anchor = GridBagConstraints.WEST;

        fc2.gridy = 0;
        fc2.gridx = 0; fc2.weightx = 0.15; filterPanel.add(new JLabel("Start offset (sec)"), fc2);
        fc2.gridx = 1; fc2.weightx = 0.15; filterPanel.add(new JLabel("End offset (sec)"), fc2);
        fc2.gridx = 2; fc2.weightx = 0.25; filterPanel.add(new JLabel("Include labels"), fc2);
        fc2.gridx = 3; fc2.weightx = 0.25; filterPanel.add(new JLabel("Exclude labels"), fc2);
        fc2.gridx = 4; fc2.weightx = 0.05; filterPanel.add(new JLabel("RegExp"), fc2);
        fc2.gridx = 5; fc2.weightx = 0.15; filterPanel.add(new JLabel("Percentile (%)"), fc2);

        fc2.gridy = 1; fc2.fill = GridBagConstraints.HORIZONTAL;
        fc2.gridx = 0; filterPanel.add(startOffsetField, fc2);
        fc2.gridx = 1; filterPanel.add(endOffsetField, fc2);
        fc2.gridx = 2; filterPanel.add(includeLabelsField, fc2);
        fc2.gridx = 3; filterPanel.add(excludeLabelsField, fc2);
        fc2.gridx = 4; fc2.fill = GridBagConstraints.NONE; filterPanel.add(regExpBox, fc2);
        fc2.gridx = 5; fc2.fill = GridBagConstraints.HORIZONTAL; filterPanel.add(percentileField, fc2);

        // ── Top wrapper ────────────────────────────────────────────────────
        JPanel topWrapper = new JPanel(new BorderLayout(0, 0));
        JPanel titleAndFile = new JPanel(new BorderLayout(0, 0));
        titleAndFile.add(titleBar, BorderLayout.NORTH);
        titleAndFile.add(filePanel, BorderLayout.CENTER);
        topWrapper.add(titleAndFile, BorderLayout.NORTH);
        topWrapper.add(filterPanel, BorderLayout.CENTER);

        // ── Results table ──────────────────────────────────────────────────
        // Table will be populated when JTL file is loaded

        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 200));

        // ── Bottom bar ─────────────────────────────────────────────────────
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveTableDataBtn = new JButton("Save Table Data");
        saveTableHeaderBox.setSelected(true);
        bottom.add(includeGroupNameBox);
        bottom.add(saveTableDataBtn);
        bottom.add(saveTableHeaderBox);

        root.add(topWrapper, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private void updatePercentileColumn() {
        String percentile = percentileField.getText().trim();
        if (percentile.isEmpty()) {
            percentile = "90";
        }
        String columnName = percentile + "% Line";
        resultsTable.getColumnModel().getColumn(5).setHeaderValue(columnName);
        resultsTable.getTableHeader().repaint();
    }

    private void refreshTableData() {
        if (cachedResults.isEmpty()) {
            return; // No data loaded yet
        }

        try {
            int percentile = Integer.parseInt(percentileField.getText().trim());
            populateTableWithResults(cachedResults, percentile);
        } catch (NumberFormatException e) {
            // Invalid percentile value, do nothing
        }
    }

    private void populateTableWithResults(Map<String, AggregateResult> results, int percentile) {
        // Clear existing data
        tableModel.setRowCount(0);

        // Format numbers
        DecimalFormat df0 = new DecimalFormat("#");
        DecimalFormat df1 = new DecimalFormat("#.0");
        DecimalFormat df2 = new DecimalFormat("#.##");

        // Add rows to table with dynamic percentile calculation
        for (AggregateResult result : results.values()) {
            Object[] row = new Object[]{
                result.getLabel(),                          // Transaction Name
                result.getCount(),                          // Transaction Count
                df0.format(result.getAverage()),            // Average
                result.getMin(),                            // Min
                result.getMax(),                            // Max
                df0.format(result.getPercentile(percentile)), // X% Line (dynamically calculated)
                df1.format(result.getStdDev()),             // Std. Dev.
                df2.format(result.getErrorPercentage()) + "%",  // Error %
                df1.format(result.getThroughput()) + "/sec" // Throughput
            };
            tableModel.addRow(row);
        }
    }

    private void loadJTLFile(String filePath) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Clear existing data
                tableModel.setRowCount(0);

                // Parse file with filter options
                JTLParser parser = new JTLParser();
                JTLParser.FilterOptions options = new JTLParser.FilterOptions();

                options.includeLabels = includeLabelsField.getText().trim();
                options.excludeLabels = excludeLabelsField.getText().trim();
                options.regExp = regExpBox.isSelected();

                try {
                    options.percentile = Integer.parseInt(percentileField.getText().trim());
                } catch (NumberFormatException e) {
                    options.percentile = 90;
                }

                Map<String, AggregateResult> results = parser.parse(filePath, options);

                // Cache results for dynamic percentile updates
                cachedResults = results;

                // Populate table with results
                populateTableWithResults(results, options.percentile);

                JOptionPane.showMessageDialog(null,
                    "Loaded " + results.size() + " transaction types from JTL file",
                    "Success", JOptionPane.INFORMATION_MESSAGE);

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                    "Error loading JTL file:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            JFrame frame = new JFrame("Advanced Aggregate Report — UI Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new UIPreview().buildUI());
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 500));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            System.out.println("[UI PREVIEW] Window opened successfully.");
        });
    }
}
