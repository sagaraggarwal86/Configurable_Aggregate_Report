package com.personal.jmeter.listener;

import org.apache.jmeter.visualizers.SamplingStatCalculator;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Handles table population, sorting, and column visibility for the
 * Configurable Aggregate Report results table.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: data rendering only — no I/O,
 * no network, no file access.</p>
 */
final class TablePopulator {

    private static final String TOTAL_LABEL = "TOTAL";

    /** DecimalFormat for integer-rounded values (response times). */
    private static final DecimalFormat FORMAT_INTEGER = new DecimalFormat("#");
    /** DecimalFormat for one decimal place (std deviation). */
    private static final DecimalFormat FORMAT_ONE_DP   = new DecimalFormat("0.0");
    /** DecimalFormat for two decimal places (error rate). */
    private static final DecimalFormat FORMAT_TWO_DP   = new DecimalFormat("0.00");

    private final DefaultTableModel tableModel;
    private final JTable resultsTable;
    private final TableColumn[] allTableColumns;
    private final JCheckBoxMenuItem[] columnMenuItems;

    private int sortColumn = -1;
    private boolean sortAscending = true;

    /**
     * Constructs the populator with references to the shared table components.
     *
     * @param tableModel      the table data model; must not be null
     * @param resultsTable    the JTable component; must not be null
     * @param allTableColumns all column objects (visible and hidden); must not be null
     * @param columnMenuItems column visibility checkboxes; must not be null
     */
    TablePopulator(DefaultTableModel tableModel,
                   JTable resultsTable,
                   TableColumn[] allTableColumns,
                   JCheckBoxMenuItem[] columnMenuItems) {
        this.tableModel = tableModel;
        this.resultsTable = resultsTable;
        this.allTableColumns = allTableColumns;
        this.columnMenuItems = columnMenuItems;
    }

    // ─────────────────────────────────────────────────────────────
    // Table population
    // ─────────────────────────────────────────────────────────────

    /**
     * Clears and repopulates the table from the given results map.
     *
     * @param results    per-label aggregated statistics
     * @param percentile percentile fraction to display (1–99)
     * @param searchPat  transaction name filter pattern (blank = show all)
     * @param useRegex   {@code true} to treat {@code searchPat} as regex
     */
    void populate(Map<String, SamplingStatCalculator> results,
                  int percentile, String searchPat, boolean useRegex) {
        tableModel.setRowCount(0);
        double pFraction = percentile / 100.0;

        List<Object[]> dataRows = new ArrayList<>();
        Object[] totalRow = null;

        for (SamplingStatCalculator calc : results.values()) {
            if (calc.getCount() == 0) continue;
            String label = calc.getLabel();
            boolean isTotal = TOTAL_LABEL.equals(label);
            if (!isTotal && !TransactionFilter.matches(label, searchPat, useRegex)) continue;

            Object[] row = buildRow(calc, pFraction);
            if (isTotal) {
                totalRow = row;
            } else {
                dataRows.add(row);
            }
        }

        sortIfNeeded(dataRows);
        dataRows.forEach(tableModel::addRow);
        if (totalRow != null) tableModel.addRow(totalRow);
    }

    private Object[] buildRow(SamplingStatCalculator calc, double pFraction) {
        long total  = calc.getCount();
        long failed = Math.round(calc.getErrorPercentage() * total);
        return new Object[]{
                calc.getLabel(),
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
    }

    private void sortIfNeeded(List<Object[]> dataRows) {
        if (sortColumn < 0 || sortColumn >= AggregateReportPanel.ALL_COLUMNS.length) return;
        final int col = sortColumn;
        final boolean asc = sortAscending;
        dataRows.sort((a, b) -> {
            int cmp = compareTableValues(a[col], b[col]);
            return asc ? cmp : -cmp;
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Sorting
    // ─────────────────────────────────────────────────────────────

    /**
     * Toggles sort direction or sets a new sort column and repopulates.
     *
     * @param viewCol  the clicked view-column index
     * @param repopFn  callback to repopulate the table after updating sort state
     */
    void handleHeaderClick(int viewCol, Runnable repopFn) {
        if (viewCol < 0) return;
        int modelCol = resultsTable.convertColumnIndexToModel(viewCol);
        if (modelCol == sortColumn) {
            sortAscending = !sortAscending;
        } else {
            sortColumn = modelCol;
            sortAscending = true;
        }
        repopFn.run();
    }

    // ─────────────────────────────────────────────────────────────
    // Column visibility
    // ─────────────────────────────────────────────────────────────

    /**
     * Stores a snapshot of all columns from the table's column model.
     * Must be called after the table has been added to its parent container.
     */
    void storeOriginalColumns() {
        TableColumnModel cm = resultsTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            allTableColumns[i] = cm.getColumn(i);
        }
    }

    /**
     * Shows or hides the column at {@code colIndex}, maintaining the original order.
     *
     * @param colIndex index into {@link AggregateReportPanel#ALL_COLUMNS}
     * @param visible  {@code true} to show, {@code false} to hide
     */
    void toggleColumnVisibility(int colIndex, boolean visible) {
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

    // ─────────────────────────────────────────────────────────────
    // Row snapshot
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of the currently visible rows (TOTAL excluded) as
     * unmodifiable {@code String[]} arrays.
     *
     * @return unmodifiable list of visible data rows
     */
    List<String[]> getVisibleRows() {
        List<String[]> rows = new ArrayList<>(tableModel.getRowCount());
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            Object nameCell = tableModel.getValueAt(r, 0);
            if (TOTAL_LABEL.equals(nameCell != null ? nameCell.toString() : "")) continue;
            String[] cells = new String[AggregateReportPanel.ALL_COLUMNS.length];
            for (int c = 0; c < AggregateReportPanel.ALL_COLUMNS.length; c++) {
                Object v = tableModel.getValueAt(r, c);
                cells[c] = v != null ? v.toString() : "";
            }
            rows.add(cells);
        }
        return Collections.unmodifiableList(rows);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

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
}
