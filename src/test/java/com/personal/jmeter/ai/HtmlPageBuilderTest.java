package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.personal.jmeter.parser.JTLParser;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlPageBuilder#convertPipeTablesToHtml},
 * {@link HtmlPageBuilder#stripRawHtml}, and {@link HtmlPageBuilder#buildChartsSection}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * the pipe-table pre-processor's branching: separator-row detection, multi-row
 * tables, cell escaping, and non-table lines pass-through.
 * Also verifies that {@code stripRawHtml} removes raw HTML blocks while
 * preserving Markdown content, and that {@code buildChartsSection} always
 * renders a section header regardless of bucket count.</p>
 */
@DisplayName("HtmlPageBuilder — convertPipeTablesToHtml")
class HtmlPageBuilderTest {

    // ─────────────────────────────────────────────────────────────
    // Null / empty input
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty and null input")
    class EmptyInputTests {

        @Test
        @DisplayName("null input returns empty string")
        void nullReturnsEmpty() {
            assertEquals("", HtmlPageBuilder.convertPipeTablesToHtml(null));
        }

        @Test
        @DisplayName("empty string returns single newline (one empty line processed)")
        void emptyReturnsSingleNewline() {
            assertEquals("\n", HtmlPageBuilder.convertPipeTablesToHtml(""));
        }

        @Test
        @DisplayName("blank lines pass through unchanged")
        void blankLinesPassThrough() {
            String input = "\n\n\n";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // All blank lines should pass through (each gets a newline appended)
            assertFalse(result.contains("<table>"), "No table should be generated for blank lines");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Non-table pass-through
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Non-table pass-through")
    class NonTableTests {

        @Test
        @DisplayName("plain text passes through without table tags")
        void plainTextPassThrough() {
            String input = "This is plain text.\nNo tables here.";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertFalse(result.contains("<table>"));
            assertTrue(result.contains("This is plain text."));
            assertTrue(result.contains("No tables here."));
        }

        @Test
        @DisplayName("pipe row without separator row on next line is not a table")
        void pipeRowWithoutSeparatorNotTable() {
            String input = "| header1 | header2 |\n| data1 | data2 |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // Without a separator row (|---|---|) on line 2, this is not a table
            assertFalse(result.contains("<table>"),
                    "Without a separator row after the header, no table should be generated");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Table detection and conversion
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Table detection and rendering")
    class TableDetectionTests {

        @Test
        @DisplayName("valid two-row pipe table is converted to HTML table")
        void validTwoRowTable() {
            String input = "| Name | Value |\n|---|---|\n| CPU | 80% |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should contain an HTML table");
            assertTrue(result.contains("<thead>"), "Should contain thead");
            assertTrue(result.contains("<tbody>"), "Should contain tbody");
            assertTrue(result.contains("<th>Name</th>"), "Header cell should contain 'Name'");
            assertTrue(result.contains("<td>CPU</td>"), "Data cell should contain 'CPU'");
            assertTrue(result.contains("<td>80%</td>"), "Data cell should contain '80%'");
        }

        @Test
        @DisplayName("multi-row table has correct number of body rows")
        void multiRowTable() {
            String input = "| Metric | Value |\n|---|---|\n| TPS | 100 |\n| Avg | 250 |\n| Max | 900 |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // Count <tr> occurrences: 1 header + 3 body = 4 total
            long trCount = result.chars().filter(c -> c == '<').count();
            assertTrue(result.contains("<td>TPS</td>"), "First body row");
            assertTrue(result.contains("<td>250</td>"), "Second body row");
            assertTrue(result.contains("<td>900</td>"), "Third body row");
        }

        @Test
        @DisplayName("separator row with colons (alignment hints) is recognised")
        void separatorWithColonsRecognised() {
            String input = "| Left | Center | Right |\n|:---|:---:|---:|\n| a | b | c |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"),
                    "Separator with colons should still be recognised as a valid separator");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Cell escaping
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cell content escaping")
    class CellEscapingTests {

        @Test
        @DisplayName("HTML special characters in cells are escaped")
        void htmlSpecialCharsEscaped() {
            String input = "| Header |\n|---|\n| <script>alert('xss')</script> |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertFalse(result.contains("<script>"),
                    "Script tags in cell content must be escaped");
            assertTrue(result.contains("&lt;script&gt;"),
                    "Escaped script tag should appear in output");
        }

        @Test
        @DisplayName("ampersands in cell content are escaped")
        void ampersandsEscaped() {
            String input = "| Key |\n|---|\n| A & B |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("A &amp; B"), "Ampersands should be escaped");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Mixed content
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed table and non-table content")
    class MixedContentTests {

        @Test
        @DisplayName("text before and after table is preserved")
        void textAroundTablePreserved() {
            String input = "Before the table.\n| H1 |\n|---|\n| D1 |\nAfter the table.";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("Before the table."));
            assertTrue(result.contains("<table>"));
            assertTrue(result.contains("After the table."));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // stripRawHtml
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripRawHtml — raw HTML block removal")
    class StripRawHtmlTests {

        @Test
        @DisplayName("null input returns null unchanged")
        void nullReturnsNull() {
            assertNull(HtmlPageBuilder.stripRawHtml(null));
        }

        @Test
        @DisplayName("blank input returns blank unchanged")
        void blankReturnsBlank() {
            String blank = "   ";
            assertEquals(blank, HtmlPageBuilder.stripRawHtml(blank));
        }

        @Test
        @DisplayName("opening script tag line is stripped")
        void scriptTagStripped() {
            String input = "Some text\n<script>alert('xss')</script>\nMore text";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("<script>"), "script tag line must be stripped");
            assertTrue(result.contains("Some text"),  "preceding text must be preserved");
            assertTrue(result.contains("More text"),  "following text must be preserved");
        }

        @Test
        @DisplayName("iframe tag line is stripped")
        void iframeTagStripped() {
            String input = "Before\n<iframe src=\"evil.com\"></iframe>\nAfter";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("<iframe"), "iframe line must be stripped");
            assertTrue(result.contains("Before"));
            assertTrue(result.contains("After"));
        }

        @Test
        @DisplayName("closing tag line is stripped")
        void closingTagStripped() {
            String input = "Before\n</div>\nAfter";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("</div>"), "closing tag line must be stripped");
            assertTrue(result.contains("Before"));
            assertTrue(result.contains("After"));
        }

        @Test
        @DisplayName("pipe table rows are preserved — they start with | not <")
        void pipeTableRowPreserved() {
            String input = "| Header |\n|---|\n| Cell |";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertTrue(result.contains("| Header |"), "pipe table header must be preserved");
            assertTrue(result.contains("| Cell |"),   "pipe table cell must be preserved");
        }

        @Test
        @DisplayName("inline HTML within a sentence is preserved — only full-line blocks are stripped")
        void inlineHtmlInSentencePreserved() {
            String input = "Use **bold** and check <a href=\"x\">link</a> inline.";
            String result = HtmlPageBuilder.stripRawHtml(input);
            // Line starts with 'U', not '<' — should pass through unchanged
            assertTrue(result.contains("inline."), "inline HTML in prose must not be stripped");
        }

        @Test
        @DisplayName("plain Markdown prose is fully preserved")
        void markdownProsePreserved() {
            String input = "## Heading\n\nSome **bold** and _italic_ text.\n\n- item 1\n- item 2";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertTrue(result.contains("## Heading"));
            assertTrue(result.contains("Some **bold**"));
            assertTrue(result.contains("- item 1"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildChartsSection — always renders a section
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildChartsSection — always renders a section header")
    class ChartsSectionTests {

        @Test
        @DisplayName("null input renders placeholder, not empty string")
        void nullRendersPlaceholder() {
            String result = HtmlPageBuilder.buildChartsSection(null);
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("<h2>Performance Charts Over Time</h2>"),
                    "Section header must always be present");
            assertTrue(result.contains("charts-unavailable"),
                    "Placeholder CSS class must be applied");
        }

        @Test
        @DisplayName("empty list renders placeholder, not empty string")
        void emptyListRendersPlaceholder() {
            String result = HtmlPageBuilder.buildChartsSection(Collections.emptyList());
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("<h2>Performance Charts Over Time</h2>"),
                    "Section header must always be present");
            assertTrue(result.contains("charts-unavailable"),
                    "Placeholder CSS class must be applied");
        }

        @Test
        @DisplayName("single bucket renders placeholder — insufficient for time-series")
        void singleBucketRendersPlaceholder() {
            JTLParser.TimeBucket bucket = new JTLParser.TimeBucket(
                    System.currentTimeMillis(), 250.0, 2.5, 10.0, 50.0);
            String result = HtmlPageBuilder.buildChartsSection(List.of(bucket));
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("charts-unavailable"),
                    "Single-bucket result must use the placeholder");
            assertFalse(result.contains("<canvas"),
                    "Single bucket must not render Chart.js canvas elements");
        }

        @Test
        @DisplayName("two or more buckets renders full Chart.js section")
        void twoBucketsRendersCharts() {
            long now = System.currentTimeMillis();
            List<JTLParser.TimeBucket> buckets = List.of(
                    new JTLParser.TimeBucket(now,          250.0, 0.0, 10.0, 50.0),
                    new JTLParser.TimeBucket(now + 30_000, 300.0, 1.0, 12.0, 60.0));
            String result = HtmlPageBuilder.buildChartsSection(buckets);
            assertTrue(result.contains("<canvas"),
                    "Two buckets must render Chart.js canvas elements");
            assertFalse(result.contains("charts-unavailable"),
                    "Full chart must not use the placeholder CSS class");
            assertTrue(result.contains("chartAvgRt"),  "Avg RT chart must be present");
            assertTrue(result.contains("chartErrPct"), "Error rate chart must be present");
            assertTrue(result.contains("chartTps"),    "Throughput chart must be present");
            assertTrue(result.contains("chartKb"),     "Bandwidth chart must be present");
        }
    }
}