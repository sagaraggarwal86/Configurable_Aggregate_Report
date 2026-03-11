package com.personal.jmeter.listener;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Customises the {@code FilePanel} inherited from
 * {@code AbstractVisualizer} for the Configurable Aggregate Report plugin.
 *
 * <p>Extracted from {@link ListenerGUI} to satisfy the 300-line class design
 * limit (Standard 3 SRP). Responsibility: component-tree surgery on the
 * built-in FilePanel only — no JMeter API usage beyond Swing components.</p>
 *
 * <p>All methods are package-private statics; callers do not need an instance.</p>
 */
final class FilePanelCustomizer {

    private FilePanelCustomizer() { /* static utility — not instantiable */ }

    /**
     * Hides the "Log/Display Only", "Errors", "Successes", and "Configure"
     * controls that {@code AbstractVisualizer}'s {@code FilePanel} adds but
     * are irrelevant for a JTL-only listener.
     *
     * @param container root container to search recursively
     */
    static void hideFilePanelExtras(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JCheckBox cb) {
                String text = cb.getText();
                if (text != null && (text.contains("Log")
                        || text.contains("Errors")
                        || text.contains("Successes"))) {
                    cb.setVisible(false);
                }
            } else if (comp instanceof JButton btn
                    && btn.getText() != null
                    && btn.getText().contains("Configure")) {
                btn.setVisible(false);
            } else if (comp instanceof JLabel lbl
                    && lbl.getText() != null
                    && (lbl.getText().contains("Log") || lbl.getText().contains("Display"))) {
                lbl.setVisible(false);
            }
            if (comp instanceof Container c) {
                hideFilePanelExtras(c);
            }
        }
    }

    /**
     * Replaces the Browse button's action so the file chooser opens in the
     * directory of the currently selected file (or the working directory if
     * no file is set). After the user confirms a selection the path is set
     * via {@code setFileFn} and the file is immediately loaded via
     * {@code onLoadFn} — no separate Load button is required.
     *
     * @param container    root container to search recursively
     * @param currentFile  the currently selected file path (may be null/empty)
     * @param setFileFn    callback to update the file path after selection
     * @param ownerComp    parent component for the file-chooser dialog
     * @param onLoadFn     callback to load and process the selected file
     */
    static void overrideBrowseButton(Container container, String currentFile,
                                     java.util.function.Consumer<String> setFileFn,
                                     Component ownerComp,
                                     Runnable onLoadFn) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn
                    && btn.getText() != null
                    && btn.getText().contains("Browse")) {
                for (java.awt.event.ActionListener al : btn.getActionListeners()) {
                    btn.removeActionListener(al);
                }
                btn.addActionListener(e -> {
                    File startDir = resolveStartDirectory(currentFile);
                    JFileChooser fc = new JFileChooser(startDir);
                    fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                            "JTL Files (*.jtl)", "jtl"));
                    fc.setAcceptAllFileFilterUsed(true);
                    if (fc.showOpenDialog(ownerComp) == JFileChooser.APPROVE_OPTION) {
                        setFileFn.accept(fc.getSelectedFile().getAbsolutePath());
                        onLoadFn.run();
                    }
                });
                btn.setVisible(true);
            }
            if (comp instanceof Container c) {
                overrideBrowseButton(c, currentFile, setFileFn, ownerComp, onLoadFn);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private static File resolveStartDirectory(String currentFile) {
        if (currentFile != null && !currentFile.trim().isEmpty()) {
            File parent = new File(currentFile).getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.dir"));
    }
}