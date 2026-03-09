package com.personal.jmeter.listener;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Compact functional interface for Swing {@link DocumentListener} callbacks.
 *
 * <p>Avoids anonymous class boilerplate when all three document events share
 * the same action. Extracted from {@link AggregateReportPanel} to satisfy the
 * 300-line class design limit (Standard 3 SRP).</p>
 */
@FunctionalInterface
public interface SimpleDocListener extends DocumentListener {

    /**
     * Called when the document changes (insert, remove, or style change).
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
