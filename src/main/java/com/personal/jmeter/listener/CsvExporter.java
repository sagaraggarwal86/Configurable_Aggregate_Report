package com.personal.jmeter.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles CSV export of the results table for the Configurable Aggregate Report.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: CSV file writing only.</p>
 *
 * <p>Dependencies are injected at construction time to keep this class testable
 * without a live {@link AggregateReportPanel}.</p>
 */
final class CsvExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvExporter.class);

    private final JComponent parent;
    private final DefaultTableModel tableModel;
    private final TableColumn[] allTableColumns;
    private final JCheckBoxMenuItem[] columnMenuItems;
    private final JCheckBox saveTableHeaderBox;

    /**
     * Constructs the exporter with all required references from the parent panel.
     *
     * @param parent           the Swing parent for dialogs; must not be null
     * @param tableModel       the table data model; must not be null
     * @param allTableColumns  all column objects (visible and hidden); must not be null
     * @param columnMenuItems  column visibility checkboxes; must not be null
     * @param saveTableHeaderBox checkbox controlling header row inclusion; must not be null
     */
    CsvExporter(JComponent parent,
                DefaultTableModel tableModel,
                TableColumn[] allTableColumns,
                JCheckBoxMenuItem[] columnMenuItems,
                JCheckBox saveTableHeaderBox) {
        this.parent = parent;
        this.tableModel = tableModel;
        this.allTableColumns = allTableColumns;
        this.columnMenuItems = columnMenuItems;
        this.saveTableHeaderBox = saveTableHeaderBox;
    }

    /**
     * Opens a save dialog and writes the currently visible table data to a CSV file.
     * Shows a success or error message dialog on completion.
     */
    void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(parent,
                    "No data to save. Load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "CSV Files (*.csv)", "csv"));
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(parent,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("saveTableData: error saving CSV. filePath={}, reason={}",
                        file.getAbsolutePath(), e.getMessage(), e);
                JOptionPane.showMessageDialog(parent,
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

    private List<Integer> getVisibleColumnModelIndices() {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) indices.add(i);
        }
        return indices;
    }

    /**
     * RFC 4180 CSV escaping applied to every cell.
     *
     * @param value cell value (may be null)
     * @return escaped string safe for embedding in CSV
     */
    static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
