package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JtlParserCore} static helpers.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * CSV splitting, column mapping, filter logic, elapsed extraction, and
 * time-bucket construction.</p>
 */
@DisplayName("JtlParserCore")
class JtlParserCoreTest {

    @BeforeAll
    static void initJMeter() {
        URL propsUrl = JtlParserCoreTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // splitCsvLine
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("splitCsvLine")
    class SplitCsvLineTests {

        @Test
        @DisplayName("plain comma-separated values are split correctly")
        void plainValues() {
            String[] result = JtlParserCore.splitCsvLine("a,b,c");
            assertArrayEquals(new String[]{"a", "b", "c"}, result);
        }

        @Test
        @DisplayName("quoted field containing comma is treated as single field")
        void quotedFieldWithComma() {
            String[] result = JtlParserCore.splitCsvLine("a,\"b,c\",d");
            assertArrayEquals(new String[]{"a", "b,c", "d"}, result);
        }

        @Test
        @DisplayName("escaped double-quote inside quoted field is unescaped")
        void escapedQuoteInsideQuotedField() {
            String[] result = JtlParserCore.splitCsvLine("a,\"b\"\"c\",d");
            assertArrayEquals(new String[]{"a", "b\"c", "d"}, result);
        }

        @Test
        @DisplayName("empty fields produce empty strings")
        void emptyFields() {
            String[] result = JtlParserCore.splitCsvLine("a,,c");
            assertArrayEquals(new String[]{"a", "", "c"}, result);
        }

        @Test
        @DisplayName("single field with no commas returns one-element array")
        void singleField() {
            String[] result = JtlParserCore.splitCsvLine("onlyfield");
            assertArrayEquals(new String[]{"onlyfield"}, result);
        }

        @Test
        @DisplayName("fields are trimmed of surrounding whitespace")
        void fieldsAreTrimmed() {
            String[] result = JtlParserCore.splitCsvLine(" a , b , c ");
            assertArrayEquals(new String[]{"a", "b", "c"}, result);
        }

        @Test
        @DisplayName("empty string returns one empty-string element")
        void emptyString() {
            String[] result = JtlParserCore.splitCsvLine("");
            assertEquals(1, result.length);
            assertEquals("", result[0]);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildColumnMap
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildColumnMap")
    class BuildColumnMapTests {

        @Test
        @DisplayName("maps header names to zero-based indices")
        void mapsHeadersToIndices() {
            Map<String, Integer> map = JtlParserCore.buildColumnMap(
                    new String[]{"timeStamp", "elapsed", "label"});
            assertEquals(0, map.get("timeStamp"));
            assertEquals(1, map.get("elapsed"));
            assertEquals(2, map.get("label"));
        }

        @Test
        @DisplayName("header names are trimmed before mapping")
        void headersTrimmed() {
            Map<String, Integer> map = JtlParserCore.buildColumnMap(
                    new String[]{" timeStamp ", " label "});
            assertTrue(map.containsKey("timeStamp"));
            assertTrue(map.containsKey("label"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseElapsed
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseElapsed")
    class ParseElapsedTests {

        private static final Map<String, Integer> COL_MAP = JtlParserCore.buildColumnMap(
                new String[]{"timeStamp", "elapsed", "label", "responseCode",
                        "responseMessage", "threadName", "dataType",
                        "success", "bytes", "sentBytes", "Latency", "IdleTime", "Connect"});

        @Test
        @DisplayName("returns elapsed value from a valid CSV line")
        void returnsElapsed() {
            long ts = System.currentTimeMillis();
            String line = ts + ",350,Login,200,OK,t-1,text,true,1024,512,300,0,50";
            assertEquals(350L, JtlParserCore.parseElapsed(line, COL_MAP));
        }

        @Test
        @DisplayName("returns 0 for null line")
        void nullLineReturnsZero() {
            assertEquals(0L, JtlParserCore.parseElapsed(null, COL_MAP));
        }

        @Test
        @DisplayName("returns 0 for blank line")
        void blankLineReturnsZero() {
            assertEquals(0L, JtlParserCore.parseElapsed("   ", COL_MAP));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // shouldInclude
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldInclude")
    class ShouldIncludeTests {

        private static SampleResult sampleAt(long ts, String label) {
            SampleResult sr = new SampleResult();
            sr.setTimeStamp(ts);
            sr.setStampAndTime(ts, 100);
            sr.setSampleLabel(label);
            return sr;
        }

        @Test
        @DisplayName("no offsets — all samples included")
        void noOffsets() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.minTimestamp = 1000L;
            SampleResult sr = sampleAt(1000L, "Login");
            assertTrue(JtlParserCore.shouldInclude(sr, opts));
        }

        @Test
        @DisplayName("startOffset excludes sample before offset")
        void startOffsetExcludes() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.startOffset  = 10;
            opts.minTimestamp = 0L;
            // sample at t=5s — before the 10s offset
            SampleResult sr = sampleAt(5_000L, "Login");
            assertFalse(JtlParserCore.shouldInclude(sr, opts));
        }

        @Test
        @DisplayName("startOffset includes sample at or after offset")
        void startOffsetIncludes() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.startOffset  = 10;
            opts.minTimestamp = 0L;
            // sample at t=15s — after the 10s offset
            SampleResult sr = sampleAt(15_000L, "Login");
            assertTrue(JtlParserCore.shouldInclude(sr, opts));
        }

        @Test
        @DisplayName("endOffset excludes sample after offset")
        void endOffsetExcludes() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.endOffset    = 30;
            opts.minTimestamp = 0L;
            // sample at t=40s — beyond the 30s end offset
            SampleResult sr = sampleAt(40_000L, "Login");
            assertFalse(JtlParserCore.shouldInclude(sr, opts));
        }

        @Test
        @DisplayName("includeLabels plain text filters by substring")
        void includeLabelSubstring() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.includeLabels = "Login";
            SampleResult match    = sampleAt(0, "Login Flow");
            SampleResult noMatch  = sampleAt(0, "Checkout");
            assertTrue(JtlParserCore.shouldInclude(match,   opts));
            assertFalse(JtlParserCore.shouldInclude(noMatch, opts));
        }

        @Test
        @DisplayName("excludeLabels plain text filters by substring")
        void excludeLabelSubstring() {
            JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
            opts.excludeLabels = "Checkout";
            SampleResult excluded = sampleAt(0, "Checkout Flow");
            SampleResult included = sampleAt(0, "Login");
            assertFalse(JtlParserCore.shouldInclude(excluded, opts));
            assertTrue(JtlParserCore.shouldInclude(included,  opts));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildTimeBuckets
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildTimeBuckets")
    class BuildTimeBucketsTests {

        @Test
        @DisplayName("empty bucketMap produces empty list")
        void emptyMapProducesEmptyList() {
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(new TreeMap<>(), 30_000L);
            assertTrue(buckets.isEmpty());
        }

        @Test
        @DisplayName("single bucket has correct avgResponseMs")
        void singleBucketAvgResponseMs() {
            TreeMap<Long, long[]> map = new TreeMap<>();
            // acc: [totalElapsed, count, errors, bytes]
            map.put(0L, new long[]{600L, 2L, 0L, 2048L}); // avg = 300ms
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(map, 30_000L);
            assertEquals(1, buckets.size());
            assertEquals(300.0, buckets.get(0).avgResponseMs, 0.01);
        }

        @Test
        @DisplayName("single bucket has correct errorPct")
        void singleBucketErrorPct() {
            TreeMap<Long, long[]> map = new TreeMap<>();
            // 1 error out of 4 samples = 25%
            map.put(0L, new long[]{400L, 4L, 1L, 1024L});
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(map, 30_000L);
            assertEquals(25.0, buckets.get(0).errorPct, 0.01);
        }

        @Test
        @DisplayName("single bucket has correct TPS")
        void singleBucketTps() {
            TreeMap<Long, long[]> map = new TreeMap<>();
            // 30 samples in a 30s bucket = 1.0 TPS
            map.put(0L, new long[]{3000L, 30L, 0L, 0L});
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(map, 30_000L);
            assertEquals(1.0, buckets.get(0).tps, 0.01);
        }

        @Test
        @DisplayName("buckets are ordered by epoch ascending")
        void bucketsOrderedAscending() {
            TreeMap<Long, long[]> map = new TreeMap<>();
            map.put(60_000L, new long[]{100L, 1L, 0L, 0L});
            map.put(30_000L, new long[]{200L, 1L, 0L, 0L});
            map.put(0L,      new long[]{300L, 1L, 0L, 0L});
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(map, 30_000L);
            assertEquals(3, buckets.size());
            assertEquals(0L,      buckets.get(0).epochMs);
            assertEquals(30_000L, buckets.get(1).epochMs);
            assertEquals(60_000L, buckets.get(2).epochMs);
        }

        @Test
        @DisplayName("zero-count bucket produces zero avgResponseMs without division by zero")
        void zeroBucketCountNoDivisionByZero() {
            TreeMap<Long, long[]> map = new TreeMap<>();
            map.put(0L, new long[]{0L, 0L, 0L, 0L});
            assertDoesNotThrow(() -> JtlParserCore.buildTimeBuckets(map, 30_000L));
            List<JTLParser.TimeBucket> buckets =
                    JtlParserCore.buildTimeBuckets(map, 30_000L);
            assertEquals(0.0, buckets.get(0).avgResponseMs);
        }
    }
}
