package com.personal.jmeter.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CsvExporter#escapeCSV(String)}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * RFC 4180 CSV escaping logic.</p>
 */
@DisplayName("CsvExporter — escapeCSV")
class CsvExporterTest {

    // ─────────────────────────────────────────────────────────────
    // Null / empty input
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null and empty input")
    class NullAndEmptyTests {

        @Test
        @DisplayName("null returns empty string")
        void nullReturnsEmpty() {
            assertEquals("", CsvExporter.escapeCSV(null));
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyReturnsEmpty() {
            assertEquals("", CsvExporter.escapeCSV(""));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Plain values — no quoting needed
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plain values (no quoting needed)")
    class PlainValueTests {

        @Test
        @DisplayName("plain text is returned unchanged")
        void plainTextUnchanged() {
            assertEquals("Login", CsvExporter.escapeCSV("Login"));
        }

        @Test
        @DisplayName("numeric string is returned unchanged")
        void numericUnchanged() {
            assertEquals("12345", CsvExporter.escapeCSV("12345"));
        }

        @Test
        @DisplayName("value with spaces but no special chars is returned unchanged")
        void spacesUnchanged() {
            assertEquals("Login Flow", CsvExporter.escapeCSV("Login Flow"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Values requiring quoting
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Values requiring quoting")
    class QuotingTests {

        @Test
        @DisplayName("value containing comma is wrapped in double quotes")
        void commaIsQuoted() {
            String result = CsvExporter.escapeCSV("a,b");
            assertEquals("\"a,b\"", result);
        }

        @Test
        @DisplayName("value containing double-quote has quote escaped and is wrapped")
        void doubleQuoteIsEscaped() {
            // RFC 4180: embedded " → "" and the whole field is quoted
            String result = CsvExporter.escapeCSV("say \"hello\"");
            assertEquals("\"say \"\"hello\"\"\"", result);
        }

        @Test
        @DisplayName("value containing newline is wrapped in double quotes")
        void newlineIsQuoted() {
            String result = CsvExporter.escapeCSV("line1\nline2");
            assertEquals("\"line1\nline2\"", result);
        }

        @Test
        @DisplayName("value containing both comma and quote is handled correctly")
        void commaAndQuoteCombined() {
            String result = CsvExporter.escapeCSV("a,\"b\"");
            assertEquals("\"a,\"\"b\"\"\"", result);
        }

        @Test
        @DisplayName("value with only a double-quote is escaped and wrapped")
        void singleQuoteOnly() {
            String result = CsvExporter.escapeCSV("\"");
            assertEquals("\"\"\"\"", result);
        }
    }
}
