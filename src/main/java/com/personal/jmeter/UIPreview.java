package com.personal.jmeter;

import com.personal.jmeter.listener.AggregateReportPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.net.URL;

/**
 * Standalone preview of the Configurable Aggregate Report UI.
 *
 * <p>Run {@link #main(String[])} directly — no JMeter runtime needed.
 * All shared UI and logic is in {@link AggregateReportPanel}; this class
 * only adds the frame, title bar, and a simple file-browse panel.</p>
 */
public class UIPreview {

    private static final Logger log = LoggerFactory.getLogger(UIPreview.class);

    /**
     * Minimum window width in pixels.
     */
    private static final int WINDOW_MIN_WIDTH = 960;
    /**
     * Minimum window height in pixels.
     */
    private static final int WINDOW_MIN_HEIGHT = 500;

    private final AggregateReportPanel reportPanel = new AggregateReportPanel();
    private final JTextField fileField = new JTextField("", 40);

    // ─────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────

    /**
     * Application entry point for the standalone UI preview.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        URL propsUrl = UIPreview.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            org.apache.jmeter.util.JMeterUtils.initLocale();
        } else {
            log.info("main: jmeter.properties not found on classpath — skipping JMeter init.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                log.warn("main: Could not set system look-and-feel. reason={}", e.getMessage());
            }
            JFrame frame = new JFrame("Configurable Aggregate Report");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new UIPreview().buildUI());
            frame.pack();
            frame.setMinimumSize(new Dimension(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            log.info("main: Window opened successfully.");
        });
    }

    private static File resolveStartDirectory(String currentPath) {
        if (!currentPath.isEmpty()) {
            File parent = new File(currentPath).getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.dir"));
    }

    private static JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(AggregateReportPanel.FONT_REGULAR);
        return l;
    }

    private static JTextField makeTextField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setFont(AggregateReportPanel.FONT_REGULAR);
        return f;
    }

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel topWrapper = new JPanel(new BorderLayout());
        topWrapper.add(buildTitleBar(), BorderLayout.NORTH);
        topWrapper.add(buildFilePanel(), BorderLayout.CENTER);
        root.add(topWrapper, BorderLayout.NORTH);
        root.add(reportPanel, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildTitleBar() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Configurable Aggregate Report");
        title.setFont(AggregateReportPanel.FONT_HEADER.deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.WEST);
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        nameRow.add(makeLabel("Name:"));
        nameRow.add(makeTextField("Configurable Aggregate Report", 28));
        nameRow.add(makeLabel("Comments:"));
        nameRow.add(makeTextField("", 28));
        panel.add(nameRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Write results to file / Read from file");
        border.setTitleFont(AggregateReportPanel.FONT_HEADER);
        panel.setBorder(border);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(makeLabel("Filename"), c);
        fileField.setFont(AggregateReportPanel.FONT_REGULAR);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        panel.add(fileField, c);
        JButton browseBtn = new JButton("Browse...");
        browseBtn.setFont(AggregateReportPanel.FONT_REGULAR);
        browseBtn.addActionListener(e -> browseJtl());
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(browseBtn, c);
        return panel;
    }

    private void browseJtl() {
        File startDir = resolveStartDirectory(fileField.getText().trim());
        JFileChooser fc = new JFileChooser(startDir);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JTL Files (*.jtl)", "jtl"));
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            File selected = fc.getSelectedFile();
            fileField.setText(selected.getAbsolutePath());
            reportPanel.loadJtlFile(selected.getAbsolutePath(), true);
        }
    }
}
