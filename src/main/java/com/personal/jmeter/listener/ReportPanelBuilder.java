package com.personal.jmeter.listener;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static com.personal.jmeter.listener.AggregateReportPanel.*;

/**
 * Assembles the Swing sub-panels and table for {@link AggregateReportPanel}.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: UI construction only — no
 * business logic, no I/O.</p>
 *
 * <p>All mutable UI components are injected so the panel retains ownership of
 * its fields; this builder purely arranges them into containers.</p>
 */
final class ReportPanelBuilder {

    private static final int TABLE_SCROLL_WIDTH  = 900;
    private static final int TABLE_SCROLL_HEIGHT = 250;

    // ── Filter / time fields ──────────────────────────────────────
    private final JTextField startOffsetField;
    private final JTextField endOffsetField;
    private final JTextField percentileField;
    private final JTextField transactionSearchField;
    private final JCheckBox  regexCheckBox;
    private final JTextField startTimeField;
    private final JTextField endTimeField;
    private final JTextField durationField;

    // ── SLA fields ───────────────────────────────────────────────
    private final JTextField        errorPctSlaField;
    private final JComboBox<String> rtMetricCombo;
    private final JTextField        rtThresholdSlaField;

    // ── Table ────────────────────────────────────────────────────
    private final JTable              resultsTable;
    private final JCheckBoxMenuItem[] columnMenuItems;
    private final TableColumn[]       allTableColumns;
    private final TablePopulator      tablePopulator;

    // ─────────────────────────────────────────────────────────────
    // Header-click callback
    // ─────────────────────────────────────────────────────────────

    /**
     * Callback interface forwarding header-click events back to the panel.
     */
    interface HeaderClickHandler {
        /**
         * Called when the user clicks a table-header column.
         * @param viewCol the clicked view-column index
         */
        void onHeaderClick(int viewCol);
    }

    private final HeaderClickHandler headerClickHandler;

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    /**
     * Constructs the builder with all component references owned by the panel.
     */
    ReportPanelBuilder(JTextField startOffsetField,
                       JTextField endOffsetField,
                       JTextField percentileField,
                       JTextField transactionSearchField,
                       JCheckBox  regexCheckBox,
                       JTextField startTimeField,
                       JTextField endTimeField,
                       JTextField durationField,
                       JTextField        errorPctSlaField,
                       JComboBox<String> rtMetricCombo,
                       JTextField        rtThresholdSlaField,
                       JTable     resultsTable,
                       JCheckBoxMenuItem[] columnMenuItems,
                       TableColumn[]       allTableColumns,
                       TablePopulator      tablePopulator,
                       HeaderClickHandler  headerClickHandler) {
        this.startOffsetField       = startOffsetField;
        this.endOffsetField         = endOffsetField;
        this.percentileField        = percentileField;
        this.transactionSearchField = transactionSearchField;
        this.regexCheckBox          = regexCheckBox;
        this.startTimeField         = startTimeField;
        this.endTimeField           = endTimeField;
        this.durationField          = durationField;
        this.errorPctSlaField       = errorPctSlaField;
        this.rtMetricCombo          = rtMetricCombo;
        this.rtThresholdSlaField    = rtThresholdSlaField;
        this.resultsTable           = resultsTable;
        this.columnMenuItems        = columnMenuItems;
        this.allTableColumns        = allTableColumns;
        this.tablePopulator         = tablePopulator;
        this.headerClickHandler     = headerClickHandler;
    }

    // ─────────────────────────────────────────────────────────────
    // Panel builders
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the Filter Settings panel — all controls in a single row.
     * Layout: Start | End | Percentile | Columns button | Search field | RegEx
     *
     * @return configured {@link JPanel}
     */
    JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        TitledBorder b = new TitledBorder("Filter Settings");
        b.setTitleFont(FONT_REGULAR);
        panel.setBorder(b);

        panel.add(compactLabel("Start Offset (s):"));
        startOffsetField.setFont(FONT_REGULAR);
        startOffsetField.setColumns(6);
        startOffsetField.setToolTipText("Exclude samples before this offset (seconds from test start)");
        panel.add(startOffsetField);

        panel.add(Box.createHorizontalStrut(6));
        panel.add(compactLabel("End Offset (s):"));
        endOffsetField.setFont(FONT_REGULAR);
        endOffsetField.setColumns(6);
        endOffsetField.setToolTipText("Exclude samples after this offset (seconds from test start)");
        panel.add(endOffsetField);

        panel.add(Box.createHorizontalStrut(6));
        panel.add(compactLabel("Percentile (%):"));
        percentileField.setFont(FONT_REGULAR);
        percentileField.setColumns(4);
        panel.add(percentileField);

        panel.add(Box.createHorizontalStrut(6));
        panel.add(buildColumnDropdown());

        panel.add(Box.createHorizontalStrut(6));
        panel.add(compactLabel("Search:"));
        transactionSearchField.setFont(FONT_REGULAR);
        transactionSearchField.setColumns(20);
        transactionSearchField.setToolTipText(
                "Filter table by transaction name. Supports plain text and RegEx.");
        panel.add(transactionSearchField);

        regexCheckBox.setFont(FONT_REGULAR);
        regexCheckBox.setToolTipText("Treat search text as a regular expression");
        panel.add(regexCheckBox);

        return panel;
    }

    /**
     * Builds a combined row containing the Test Time Info panel (CENTER)
     * and the SLA Thresholds panel (EAST) side by side.
     *
     * @return combined row panel
     */
    JPanel buildTimeInfoAndSlaRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(buildTimeInfoPanel(), BorderLayout.CENTER);
        row.add(buildSlaPanel(),      BorderLayout.EAST);
        return row;
    }

    /**
     * Builds the Test Time Info panel — compact single row with inline labels.
     * Fields are non-editable (gray background) to show read-only test metadata.
     *
     * @return configured {@link JPanel}
     */
    JPanel buildTimeInfoPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 1));
        TitledBorder b = new TitledBorder("Test Time Info");
        b.setTitleFont(FONT_REGULAR);
        panel.setBorder(b);
        panel.add(compactLabel("Start:"));
        panel.add(compactReadOnlyField(startTimeField, 14));
        panel.add(Box.createHorizontalStrut(10));
        panel.add(compactLabel("End:"));
        panel.add(compactReadOnlyField(endTimeField, 14));
        panel.add(Box.createHorizontalStrut(10));
        panel.add(compactLabel("Duration:"));
        panel.add(compactReadOnlyField(durationField, 10));
        return panel;
    }

    /**
     * Builds the SLA Thresholds panel — compact single row.
     * Layout: Error % field | Response Time dropdown | RT value field
     *
     * @return configured {@link JPanel}
     */
    JPanel buildSlaPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        TitledBorder b = new TitledBorder("SLA Thresholds");
        b.setTitleFont(FONT_REGULAR);
        panel.setBorder(b);

        panel.add(compactLabel("Error %:"));
        errorPctSlaField.setFont(FONT_REGULAR);
        errorPctSlaField.setColumns(4);
        errorPctSlaField.setToolTipText(
                "Highlight Error Rate cells exceeding this value (1–99). Leave blank to disable.");
        panel.add(errorPctSlaField);

        panel.add(Box.createHorizontalStrut(10));

        panel.add(compactLabel("Response Time:"));
        rtMetricCombo.setFont(FONT_REGULAR);
        rtMetricCombo.setToolTipText(
                "Select which response time column to compare against the threshold");
        panel.add(rtMetricCombo);

        rtThresholdSlaField.setFont(FONT_REGULAR);
        rtThresholdSlaField.setColumns(5);
        rtThresholdSlaField.setToolTipText(
                "Highlight response time cells exceeding this value (ms). Leave blank to disable.");
        panel.add(rtThresholdSlaField);

        return panel;
    }

    /**
     * Builds and configures the table scroll pane, wiring the header-click listener
     * and installing the sort-arrow header renderer.
     *
     * @return configured {@link JScrollPane} wrapping the results table
     */
    JScrollPane buildTableScrollPane() {
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20);
        resultsTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                headerClickHandler.onHeaderClick(resultsTable.columnAtPoint(e.getPoint()));
            }
        });

        // Wrap native renderer to append sort-direction arrow — preserves platform L&F.
        TableCellRenderer nativeRenderer = resultsTable.getTableHeader().getDefaultRenderer();
        resultsTable.getTableHeader().setDefaultRenderer(
                (table, value, isSelected, hasFocus, row, col) -> {
                    Component c = nativeRenderer.getTableCellRendererComponent(
                            table, value, isSelected, hasFocus, row, col);
                    int modelCol = table.convertColumnIndexToModel(col);
                    String arrow = modelCol == tablePopulator.getSortColumn()
                            ? (tablePopulator.isSortAscending() ? " \u2191" : " \u2193")
                            : " \u2195";
                    if (c instanceof JLabel lbl) {
                        lbl.setText(value + arrow);
                        lbl.setHorizontalAlignment(JLabel.CENTER);
                    }
                    return c;
                });

        JScrollPane scroll = new JScrollPane(resultsTable);
        scroll.setPreferredSize(new Dimension(TABLE_SCROLL_WIDTH, TABLE_SCROLL_HEIGHT));
        return scroll;
    }

    // ─────────────────────────────────────────────────────────────
    // Column dropdown
    // ─────────────────────────────────────────────────────────────

    private JButton buildColumnDropdown() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < ALL_COLUMNS.length; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(ALL_COLUMNS[i], true);
            item.setFont(FONT_REGULAR);
            if (i == 0) {
                item.setEnabled(false);
            } else {
                final int col = i;
                item.addActionListener(e ->
                        tablePopulator.toggleColumnVisibility(col, item.isSelected()));
            }
            columnMenuItems[i] = item;
            popup.add(item);
        }
        JButton btn = new JButton("Select Columns \u25BC");
        btn.setFont(FONT_REGULAR);
        btn.addActionListener(e -> popup.show(btn, 0, btn.getHeight()));
        return btn;
    }

    // ─────────────────────────────────────────────────────────────
    // Static layout helpers
    // ─────────────────────────────────────────────────────────────

    private static JLabel compactLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_REGULAR);
        return l;
    }

    private static JTextField compactReadOnlyField(JTextField f, int columns) {
        f.setFont(FONT_REGULAR);
        f.setEditable(false);
        f.setColumns(columns);
        f.setBackground(new Color(240, 240, 240));
        return f;
    }
}