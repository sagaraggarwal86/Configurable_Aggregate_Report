package com.Sagar.jmeter.sampler;

import com.Sagar.jmeter.data.AggregateResult;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

public class SamplePluginSamplerUI extends AbstractSamplerGui {

    // File section
    private final JTextField fileNameField       = new JTextField("", 40);

    // Filter settings
    private final JTextField startOffsetField    = new JTextField("", 10);
    private final JTextField endOffsetField      = new JTextField("", 10);
    private final JTextField includeLabelsField  = new JTextField("", 20);
    private final JTextField excludeLabelsField  = new JTextField("", 20);
    private final JCheckBox  regExpBox           = new JCheckBox();
    private final JTextField percentileField     = new JTextField("90", 10);

    // Results table
    private final String[] COLUMN_NAMES = {
        "Transaction Name", "Transaction Count", "Average", "Min", "Max",
        "90% Line", "Std. Dev.", "Error %", "Throughput"
    };
    private final DefaultTableModel tableModel   = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable resultsTable            = new JTable(tableModel);

    // Bottom controls
    private final JCheckBox  includeGroupNameBox = new JCheckBox("Include group name in label?");
    private final JCheckBox  saveTableHeaderBox  = new JCheckBox("Save Table Header");

    // Cache for loaded results to support dynamic percentile updates
    private Map<String, AggregateResult> cachedResults = new HashMap<>();

    public SamplePluginSamplerUI() {
        super();
        initComponents();
        setupListeners();
    }

    private void setupListeners() {
        // Update table column header and data when percentile value changes
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

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));
        setBorder(makeBorder());

        // Stack title + file panel + filter panel vertically at the top
        JPanel topWrapper = new JPanel(new BorderLayout(0, 0));
        JPanel titleAndFile = new JPanel(new BorderLayout(0, 0));
        titleAndFile.add(makeTitlePanel(), BorderLayout.NORTH);
        titleAndFile.add(buildFilePanel(), BorderLayout.CENTER);
        topWrapper.add(titleAndFile, BorderLayout.NORTH);
        topWrapper.add(buildFilterPanel(), BorderLayout.CENTER);

        add(topWrapper, BorderLayout.NORTH);
        add(buildTablePanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    // ── File / Read panel ────────────────────────────────────────────────────
    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Write results to file / Read from file"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        // "Filename" label
        c.gridx = 0; c.gridy = 0; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(new JLabel("Filename"), c);

        // filename text field
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        panel.add(fileNameField, c);

        // Browse button
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                fileNameField.setText(f.getAbsolutePath());
            }
        });
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(browseBtn, c);

        return panel;
    }

    // ── Filter settings panel ────────────────────────────────────────────────
    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Filter settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        // Header row
        c.gridy = 0;
        c.gridx = 0; c.weightx = 0.15;
        panel.add(new JLabel("Start offset (sec)"), c);
        c.gridx = 1; c.weightx = 0.15;
        panel.add(new JLabel("End offset (sec)"), c);
        c.gridx = 2; c.weightx = 0.25;
        panel.add(new JLabel("Include labels"), c);
        c.gridx = 3; c.weightx = 0.25;
        panel.add(new JLabel("Exclude labels"), c);
        c.gridx = 4; c.weightx = 0.05;
        panel.add(new JLabel("RegExp"), c);
        c.gridx = 5; c.weightx = 0.15;
        panel.add(new JLabel("Percentile (%)"), c);

        // Input row
        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        panel.add(startOffsetField, c);
        c.gridx = 1;
        panel.add(endOffsetField, c);
        c.gridx = 2;
        panel.add(includeLabelsField, c);
        c.gridx = 3;
        panel.add(excludeLabelsField, c);
        c.gridx = 4; c.fill = GridBagConstraints.NONE;
        panel.add(regExpBox, c);
        c.gridx = 5; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(percentileField, c);

        return panel;
    }

    // ── Results table ────────────────────────────────────────────────────────
    private JScrollPane buildTablePanel() {
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 300));
        return scrollPane;
    }

    // ── Bottom controls ──────────────────────────────────────────────────────
    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveTableDataBtn = new JButton("Save Table Data");
        saveTableHeaderBox.setSelected(true);
        bottom.add(includeGroupNameBox);
        bottom.add(saveTableDataBtn);
        bottom.add(saveTableHeaderBox);
        return bottom;
    }

    // ── AbstractSamplerGui contract ──────────────────────────────────────────
    @Override public String getLabelResource() { return "sample_plugin_sampler"; }
    @Override public String getStaticLabel()   { return "Advanced Aggregate Report"; }

    @Override
    public TestElement createTestElement() {
        SamplePluginSampler s = new SamplePluginSampler();
        modifyTestElement(s);
        return s;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        configureTestElement(el);
        if (el instanceof SamplePluginSampler s) {
            s.setFileName(fileNameField.getText().trim());
            s.setFilterSettings(buildFilterString());
            s.setStart(startOffsetField.getText().trim());
            s.setDuration(endOffsetField.getText().trim());
        }
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
        if (el instanceof SamplePluginSampler s) {
            fileNameField.setText(s.getFileName());
            startOffsetField.setText(s.getStart());
            endOffsetField.setText(s.getDuration());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        fileNameField.setText("");
        startOffsetField.setText("");
        endOffsetField.setText("");
        includeLabelsField.setText("");
        excludeLabelsField.setText("");
        percentileField.setText("90");
        regExpBox.setSelected(false);
        tableModel.setRowCount(0);
        cachedResults.clear();
    }

    private String buildFilterString() {
        return "start=" + startOffsetField.getText().trim()
             + ";end=" + endOffsetField.getText().trim()
             + ";include=" + includeLabelsField.getText().trim()
             + ";exclude=" + excludeLabelsField.getText().trim()
             + ";regExp=" + regExpBox.isSelected()
             + ";percentile=" + percentileField.getText().trim();
    }

    /**
     * Public method to cache loaded results and populate table.
     * Called when a JTL file has been parsed and loaded.
     */
    public void setAndDisplayResults(Map<String, AggregateResult> results) {
        this.cachedResults = new HashMap<>(results);
        try {
            int percentile = Integer.parseInt(percentileField.getText().trim());
            populateTableWithResults(results, percentile);
        } catch (NumberFormatException e) {
            populateTableWithResults(results, 90);
        }
    }
}
