package com.personal.jmeter.parser;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JTLParser}.
 *
 * <p>All tests use in-memory JTL files written to a temporary directory —
 * no database, no network, no persistent file system state.</p>
 */
@DisplayName("JTLParser")
class JTLParserTest {

    /**
     * Initialise {@link JMeterUtils#appProperties} once for this test class.
     *
     * <p>{@code SampleResult}'s constructor calls {@code JMeterUtils.getPropDefault()},
     * which logs a WARN and falls back to the default when {@code appProperties} is
     * {@code null}.  Loading the {@code jmeter.properties} that already sits in
     * {@code src/test/resources} is sufficient — it declares the five properties
     * {@code SampleResult} reads at construction time.</p>
     */
    @BeforeAll
    static void initJMeter() {
        URL propsUrl = JTLParserTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    private static final String CSV_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,"
                    + "threadName,dataType,success,bytes,sentBytes,Latency,IdleTime,Connect";

    @TempDir
    Path tempDir;

    private Path writeCsv(String... dataLines) throws IOException {
        Path file = tempDir.resolve("test.jtl");
        StringBuilder sb = new StringBuilder(CSV_HEADER).append(System.lineSeparator());
        for (String line : dataLines) {
            sb.append(line).append(System.lineSeparator());
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    // ─────────────────────────────────────────────────────────────
    // Input validation
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null filePath throws NullPointerException")
    void nullFilePathThrows() {
        JTLParser parser = new JTLParser();
        assertThrows(NullPointerException.class,
                () -> parser.parse(null, new JTLParser.FilterOptions()));
    }

    @Test
    @DisplayName("null options throws NullPointerException")
    void nullOptionsThrows(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("empty.jtl");
        Files.writeString(f, CSV_HEADER, StandardCharsets.UTF_8);
        JTLParser parser = new JTLParser();
        assertThrows(NullPointerException.class,
                () -> parser.parse(f.toString(), null));
    }

    @Test
    @DisplayName("empty JTL file throws IOException")
    void emptyFileThrows() throws IOException {
        Path file = tempDir.resolve("empty.jtl");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        JTLParser parser = new JTLParser();
        assertThrows(IOException.class,
                () -> parser.parse(file.toString(), new JTLParser.FilterOptions()));
    }

    // ─────────────────────────────────────────────────────────────
    // Basic parsing
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("single passing sample is aggregated correctly")
    void singlePassingSample() throws IOException {
        long ts = System.currentTimeMillis();
        Path file = writeCsv(ts + ",250,Login,200,OK,t-1,text,true,1024,512,200,0,50");

        JTLParser.ParseResult result = new JTLParser().parse(
                file.toString(), new JTLParser.FilterOptions());

        assertTrue(result.results.containsKey("Login"), "Login label expected");
        assertTrue(result.results.containsKey("TOTAL"), "TOTAL label expected");
        assertEquals(1, result.results.get("Login").getCount());
        assertEquals(1, result.results.get("TOTAL").getCount());
    }

    @Test
    @DisplayName("failed sample increments error count")
    void failedSampleErrorCount() throws IOException {
        long ts = System.currentTimeMillis();
        Path file = writeCsv(ts + ",500,Login,500,Error,t-1,text,false,100,50,450,0,30");

        JTLParser.ParseResult result = new JTLParser().parse(
                file.toString(), new JTLParser.FilterOptions());

        var calc = result.results.get("Login");
        assertNotNull(calc);
        assertTrue(calc.getErrorPercentage() > 0, "Error percentage should be > 0");
    }

    // ─────────────────────────────────────────────────────────────
    // Sub-result detection
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sub-results (Login-1, Login-2) are excluded when parent Login exists")
    void subResultsExcluded() throws IOException {
        long ts = System.currentTimeMillis();
        Path file = writeCsv(
                ts + ",100,Login,200,OK,t-1,text,true,512,128,80,0,20",
                (ts + 50) + ",40,Login-1,200,OK,t-1,text,true,200,64,35,0,10",
                (ts + 90) + ",60,Login-2,200,OK,t-1,text,true,312,64,55,0,10");

        JTLParser.ParseResult result = new JTLParser().parse(
                file.toString(), new JTLParser.FilterOptions());

        assertFalse(result.results.containsKey("Login-1"), "Login-1 is a sub-result");
        assertFalse(result.results.containsKey("Login-2"), "Login-2 is a sub-result");
        assertTrue(result.results.containsKey("Login"),    "Parent Login must be present");
    }

    // ─────────────────────────────────────────────────────────────
    // Offset filtering
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("startOffset excludes samples before the offset")
    void startOffsetExcludesSamples() throws IOException {
        long baseTs = System.currentTimeMillis();
        // sample at t=0s (before offset) and t=10s (after offset)
        Path file = writeCsv(
                baseTs + ",100,EarlyTx,200,OK,t-1,text,true,512,128,90,0,20",
                (baseTs + 10_000L) + ",100,LateTx,200,OK,t-1,text,true,512,128,90,0,20");

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset = 5; // 5 seconds

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        assertFalse(result.results.containsKey("EarlyTx"), "EarlyTx should be filtered out");
        assertTrue(result.results.containsKey("LateTx"),   "LateTx should be included");
    }

    // ─────────────────────────────────────────────────────────────
    // Time bucket generation
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("two samples in the same 30s bucket produce one bucket")
    void twoSamplesOneBucket() throws IOException {
        long baseTs = System.currentTimeMillis();
        long bucket = (baseTs / 30_000L) * 30_000L; // align to bucket boundary
        Path file = writeCsv(
                (bucket + 1000) + ",200,Tx,200,OK,t-1,text,true,512,128,180,0,20",
                (bucket + 5000) + ",300,Tx,200,OK,t-1,text,true,512,128,280,0,20");

        JTLParser.ParseResult result = new JTLParser().parse(
                file.toString(), new JTLParser.FilterOptions());

        assertEquals(1, result.timeBuckets.size(), "Expected exactly one time bucket");
    }

    @Test
    @DisplayName("time range (startTimeMs, endTimeMs, durationMs) is populated")
    void timeRangePopulated() throws IOException {
        long ts = System.currentTimeMillis();
        Path file = writeCsv(ts + ",500,Tx,200,OK,t-1,text,true,512,128,450,0,30");

        JTLParser.ParseResult result = new JTLParser().parse(
                file.toString(), new JTLParser.FilterOptions());

        assertTrue(result.startTimeMs > 0,  "startTimeMs should be set");
        assertTrue(result.endTimeMs   > 0,  "endTimeMs should be set");
        assertTrue(result.durationMs  >= 0, "durationMs should be non-negative");
    }
}
