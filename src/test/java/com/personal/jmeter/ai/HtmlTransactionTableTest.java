package com.personal.jmeter.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlReportRenderer#buildTransactionMetricsSection(List)}.
 *
 * <p>No JMeter runtime, no file system, no Swing — pure string verification.
 *
 * <p>Note: {@code List.of(row)} and {@code Arrays.asList(row)} both trigger a
 * compiler type-inference ambiguity when the element type is {@code String[]},
 * because both methods have varargs overloads. All test data is therefore built
 * via {@link #rows(String[]...)} which uses explicit {@code ArrayList} construction
 * to sidestep the issue entirely.
 */
@DisplayName("HtmlReportRenderer — buildTransactionMetricsSection")
class HtmlTransactionTableTest {

    private HtmlReportRenderer renderer;

    /**
     * Safe helper that builds a {@code List<String[]>} without triggering the
     * varargs-vs-array type-inference ambiguity present in {@code List.of} and
     * {@code Arrays.asList}.
     */
    @SafeVarargs
    private static List<String[]> rows(String[]... rowArrays) {
        List<String[]> list = new ArrayList<>(rowArrays.length);
        for (String[] r : rowArrays) {
            list.add(r);
        }
        return list;
    }

    @BeforeEach
    void setUp() {
        renderer = new HtmlReportRenderer();
    }

    // ─────────────────────────────────────────────────────────────
    // Empty / null input
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty / null input")
    class EmptyInputTests {

        @Test
        @DisplayName("null rows → empty string (section omitted)")
        void nullRowsReturnsEmpty() {
            assertEquals("", renderer.buildTransactionMetricsSection(null));
        }

        @Test
        @DisplayName("empty list → empty string (section omitted)")
        void emptyListReturnsEmpty() {
            assertEquals("", renderer.buildTransactionMetricsSection(Collections.emptyList()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTML structure
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("HTML structure")
    class StructureTests {

        private String html;

        @BeforeEach
        void buildHtml() {
            html = renderer.buildTransactionMetricsSection(rows(
                    new String[]{"Login", "100", "98", "2", "250", "120", "900",
                            "450", "55.3", "2.00%", "10.0/sec"}
            ));
        }

        @Test
        @DisplayName("contains metrics-section wrapper div")
        void containsWrapper() {
            assertTrue(html.contains("class=\"metrics-section\""));
        }

        @Test
        @DisplayName("contains h2 heading")
        void containsHeading() {
            assertTrue(html.contains("<h2>Transaction Metrics</h2>"));
        }

        @Test
        @DisplayName("contains thead and tbody")
        void containsTheadAndTbody() {
            assertTrue(html.contains("<thead>"));
            assertTrue(html.contains("<tbody>"));
        }

        @Test
        @DisplayName("contains all expected column headers")
        void containsAllHeaders() {
            for (String header : HtmlReportRenderer.TABLE_HEADERS) {
                assertTrue(html.contains(header),
                        "Expected header not found: " + header);
            }
        }

        @Test
        @DisplayName("transaction name cell has no num class")
        void transactionCellNoNumClass() {
            assertTrue(html.contains("<td>Login</td>"));
        }

        @Test
        @DisplayName("numeric cells have num class for right-alignment")
        void numericCellsHaveNumClass() {
            assertTrue(html.contains("<td class=\"num\">100</td>"));
            assertTrue(html.contains("<td class=\"num\">2.00%</td>"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Data rendering
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Data rendering")
    class DataTests {

        @Test
        @DisplayName("single row — all cells rendered")
        void singleRowAllCells() {
            String html = renderer.buildTransactionMetricsSection(rows(
                    new String[]{"Checkout", "500", "495", "5", "300", "100",
                            "1200", "650", "78.1", "1.00%", "25.0/sec"}
            ));
            assertTrue(html.contains("Checkout"));
            assertTrue(html.contains(">500<"));
            assertTrue(html.contains("1.00%"));
            assertTrue(html.contains("25.0/sec"));
        }

        @Test
        @DisplayName("multiple rows — both transactions appear")
        void multipleRowsBothPresent() {
            String html = renderer.buildTransactionMetricsSection(rows(
                    new String[]{"Login", "200", "198", "2", "100", "50", "400",
                            "180", "22.0", "1.00%", "10.0/sec"},
                    new String[]{"Checkout", "100", "100", "0", "350", "200", "800",
                            "600", "60.0", "0.00%", "5.0/sec"}
            ));
            assertTrue(html.contains("Login"));
            assertTrue(html.contains("Checkout"));
        }

        @Test
        @DisplayName("HTML special characters in transaction name are escaped")
        void htmlCharsEscaped() {
            String html = renderer.buildTransactionMetricsSection(rows(
                    new String[]{"<Script> & 'Test'", "1", "1", "0",
                            "200", "200", "200", "200", "0.0", "0.00%", "1.0/sec"}
            ));
            assertFalse(html.contains("<Script>"), "Raw <Script> tag must be escaped");
            assertTrue(html.contains("&lt;Script&gt;"), "Expected HTML-escaped tag");
            assertTrue(html.contains("&amp;"), "Expected &amp; for &");
        }

        @Test
        @DisplayName("null cell value renders as empty string")
        void nullCellRendersEmpty() {
            String[] row = new String[HtmlReportRenderer.TABLE_HEADERS.length];
            row[0] = "NullTest"; // all other cells remain null
            String html = renderer.buildTransactionMetricsSection(rows(row));
            assertTrue(html.contains("NullTest"));
        }

        @Test
        @DisplayName("short row (fewer cells than headers) renders without exception")
        void shortRowNoException() {
            String[] shortRow = {"ShortRow", "50"}; // only 2 of 11 cells supplied
            assertDoesNotThrow(() ->
                    renderer.buildTransactionMetricsSection(rows(shortRow)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // escapeHtml contract
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("escapeHtml utility")
    class EscapeHtmlTests {

        @Test
        @DisplayName("null returns empty string")
        void nullReturnsEmpty() {
            assertEquals("", HtmlReportRenderer.escapeHtml(null));
        }

        @Test
        @DisplayName("plain text unchanged")
        void plainTextUnchanged() {
            assertEquals("Hello World", HtmlReportRenderer.escapeHtml("Hello World"));
        }

        @Test
        @DisplayName("ampersand escaped")
        void ampersandEscaped() {
            assertEquals("A &amp; B", HtmlReportRenderer.escapeHtml("A & B"));
        }

        @Test
        @DisplayName("angle brackets escaped")
        void angleBracketsEscaped() {
            assertEquals("&lt;tag&gt;", HtmlReportRenderer.escapeHtml("<tag>"));
        }

        @Test
        @DisplayName("double-quote escaped to &quot;")
        void doubleQuoteEscaped() {
            assertEquals("&quot;value&quot;", HtmlReportRenderer.escapeHtml("\"value\""));
        }

        @Test
        @DisplayName("all special chars escaped together")
        void allSpecialCharsEscaped() {
            assertEquals("&lt;a&gt; &amp; &lt;b&gt;",
                    HtmlReportRenderer.escapeHtml("<a> & <b>"));
        }
    }
}