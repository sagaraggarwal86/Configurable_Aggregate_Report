package com.personal.jmeter.listener;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
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

    private static final double FILTER_FIELD_WEIGHT = 0.25;
    private static final double TIME_FIELD_WEIGHT   = 0.33;
    private static final int TABLE_SCROLL_WIDTH     = 900;
    private static final int TABLE_SCROLL_HEIGHT    = 250;

    private final JTextField startOffsetField;
    private final JTextField endOffsetField;
    private final JTextField percentileField;
    private final JTextField transactionSearchField;
    private final JCheckBox  regexCheckBox;
    private final JTextField startTimeField;
    private final JTextField endTimeField;
    private final JTextField durationField;
    private final JTable     resultsTable;
    private final JCheckBoxMenuItem[] columnMenuItems;
    private final TableColumn[]       allTableColumns;
    private final TablePopulator      tablePopulator;
    private final int headerClickColumn;

    /**
     * Callback interface forwarding header-click events back to the panel.
     */
    interface HeaderClickHandler {
        /**
         * Called when the user clicks a table-header column.
         *
         * @param viewCol the clicked view-column index
         */
        void onHeaderClick(int viewCol);
    }

    private final HeaderClickHandler headerClickHandler;

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
        this.resultsTable           = resultsTable;
        this.columnMenuItems        = columnMenuItems;
        this.allTableColumns        = allTableColumns;
        this.tablePopulator         = tablePopulator;
        this.headerClickHandler     = headerClickHandler;
        this.headerClickColumn      = -1; // unused; header click resolved via mouse point
    }

    // ─────────────────────────────────────────────────────────────
    // Panel builders
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the Filter Settings panel.
     *
     * @return configured {@link JPanel}
     */
    JPanel buildFilterPanel() {
        JPanel panel = titledPanel("Filter Settings");
        GridBagConstraints c = defaultGbc();
        c.gridy = 0;
        addLabel(panel, "Start Offset (Seconds)", 0, c);
        addLabel(panel, "End Offset (Seconds)",   1, c);
        addLabel(panel, "Percentile (%)",          2, c);
        addLabel(panel, "Visible Columns",         3, c);

        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = FILTER_FIELD_WEIGHT;
        addField(panel, startOffsetField, 0, c);
        addField(panel, endOffsetField,   1, c);
        addField(panel, percentileField,  2, c);
        c.gridx = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(buildColumnDropdown(), c);

        c.gridy = 2; c.gridx = 0; c.gridwidth = 4;
        c.fill = GridBagConstraints.NONE; c.weightx = 1.0;
        addLabel(panel, "Transaction Search", c);

        c.gridwidth = 1; c.gridy = 3; c.gridx = 0; c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        transactionSearchField.setFont(FONT_REGULAR);
        transactionSearchField.setToolTipText(
                "Filter table by transaction name. Supports plain text and RegEx.");
        panel.add(transactionSearchField, c);

        c.gridx = 3; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        regexCheckBox.setFont(FONT_REGULAR);
        regexCheckBox.setToolTipText("Treat search text as a regular expression");
        panel.add(regexCheckBox, c);
        return panel;
    }

    /**
     * Builds the Test Time Info panel.
     *
     * @return configured {@link JPanel}
     */
    JPanel buildTimeInfoPanel() {
        JPanel panel = titledPanel("Test Time Info");
        GridBagConstraints c = defaultGbc();
        c.gridy = 0;
        addLabel(panel, "Start Date/Time", 0, c);
        addLabel(panel, "End Date/Time",   1, c);
        addLabel(panel, "Duration",        2, c);
        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = TIME_FIELD_WEIGHT;
        addReadOnlyField(panel, startTimeField, 0, c);
        addReadOnlyField(panel, endTimeField,   1, c);
        addReadOnlyField(panel, durationField,  2, c);
        return panel;
    }

    /**
     * Builds and configures the table scroll pane, wiring the header-click listener.
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

    private static JPanel titledPanel(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        TitledBorder b = new TitledBorder(title);
        b.setTitleFont(FONT_HEADER);
        p.setBorder(b);
        return p;
    }

    private static GridBagConstraints defaultGbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 6, 4, 6);
        c.anchor  = GridBagConstraints.WEST;
        c.weightx = TIME_FIELD_WEIGHT;
        return c;
    }

    private static void addLabel(JPanel p, String text, int gridx, GridBagConstraints c) {
        c.gridx = gridx;
        JLabel l = new JLabel(text);
        l.setFont(FONT_REGULAR);
        p.add(l, c);
    }

    private static void addLabel(JPanel p, String text, GridBagConstraints c) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_REGULAR);
        p.add(l, c);
    }

    private static void addField(JPanel p, JTextField f, int gridx, GridBagConstraints c) {
        c.gridx = gridx;
        f.setFont(FONT_REGULAR);
        p.add(f, c);
    }

    private static void addReadOnlyField(JPanel p, JTextField f, int gridx,
                                         GridBagConstraints c) {
        c.gridx = gridx;
        f.setFont(FONT_REGULAR);
        f.setEditable(false);
        f.setBackground(new Color(240, 240, 240));
        p.add(f, c);
    }
}
