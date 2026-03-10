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

    // ─────────────────────────────────────────────────────────────
    // generateParentSample flag
    // ─────────────────────────────────────────────────────────────
    //
    // When JMeter's Transaction Controller has "Generate Parent Sample" ON, it writes
    // the controller row (dataType = "") immediately before its child HTTP sample row
    // (dataType = "text") with both rows sharing the identical timeStamp and elapsed.
    // The parser must exclude the child row (sub-result) in that case.
    //
    // When "Generate Parent Sample" is OFF the controller does NOT produce a wrapping
    // row — the child appears independently — so both rows survive aggregation.

    @Test
    @DisplayName("generateParentSample=true (default) — TC child excluded when parent row precedes it with same ts/elapsed")
    void generateParentSampleOnExcludesTransactionControllerChild() throws IOException {
        long ts = System.currentTimeMillis();
        // Row 1: Transaction Controller parent — dataType="" (column 7), same ts+elapsed as child
        // Row 2: HTTP Request child          — dataType="text",           same ts+elapsed as parent
        // The parser must identify row 2 as a sub-result and drop it.
        Path file = writeCsv(
                ts + ",100,Login - TC,200,OK,t-1,,true,512,128,80,0,20",   // controller, dataType=""
                ts + ",100,Login,200,OK,t-1,text,true,512,128,80,0,20");   // child, same ts+el

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.generateParentSample = true;   // default — explicit for clarity

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        assertTrue(result.results.containsKey("Login - TC"),
                "Transaction Controller parent must be present");
        assertFalse(result.results.containsKey("Login"),
                "Child row must be excluded when it follows a controller with same ts/elapsed");
    }

    @Test
    @DisplayName("generateParentSample=false — TC child kept even when parent row precedes it with same ts/elapsed")
    void generateParentSampleOffKeepsTransactionControllerChild() throws IOException {
        long ts = System.currentTimeMillis();
        Path file = writeCsv(
                ts + ",100,Login - TC,200,OK,t-1,,true,512,128,80,0,20",
                ts + ",100,Login,200,OK,t-1,text,true,512,128,80,0,20");

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.generateParentSample = false;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        assertTrue(result.results.containsKey("Login - TC"), "Controller row must be present");
        assertTrue(result.results.containsKey("Login"),      "Child row must be present when generateParentSample=false");
    }

    @Test
    @DisplayName("generateParentSample=true — rows with same label but different ts/elapsed are NOT treated as sub-results")
    void generateParentSampleOnIgnoresMismatchedTimestamps() throws IOException {
        long ts = System.currentTimeMillis();
        // Two rows: first has empty dataType but different elapsed → should NOT trigger detection
        Path file = writeCsv(
                ts        + ",100,Some - TC,200,OK,t-1,,true,512,128,80,0,20",   // controller
                (ts + 50) + ",75,Some,200,OK,t-1,text,true,512,128,70,0,10");    // different ts → not a child

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.generateParentSample = true;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        assertTrue(result.results.containsKey("Some - TC"), "Controller row must be present");
        assertTrue(result.results.containsKey("Some"),
                "Row with different ts must NOT be excluded — it is not a child of the controller");
    }

    @Test
    @DisplayName("generateParentSample=true — child with ts 1 ms after parent (real JMeter jitter) is still excluded")
    void generateParentSampleOnExcludesSubResultWithOneMillisecondJitter() throws IOException {
        long ts = System.currentTimeMillis();
        // JMeter sometimes writes the child row 1 ms after the parent's timestamp
        // even though they represent the same transaction. The parser must tolerate this.
        Path file = writeCsv(
                ts       + ",100,Login - TC,200,OK,t-1,,true,512,128,80,0,20",    // controller
                (ts + 1) + ",100,Login,200,OK,t-1,text,true,512,128,80,0,20");    // child ts + 1 ms

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.generateParentSample = true;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        assertTrue(result.results.containsKey("Login - TC"), "Controller row must be present");
        assertFalse(result.results.containsKey("Login"),
                "Child row must be excluded even when its ts is 1 ms after the controller's ts");
    }

    // ─────────────────────────────────────────────────────────────
    // includeTimerDuration flag
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("includeTimerDuration=true (default) — elapsed used as-is")
    void includeTimerDurationOnUsesRawElapsed() throws IOException {
        long ts = System.currentTimeMillis();
        // elapsed=500, IdleTime=200 — with flag ON, avg should reflect full 500ms
        Path file = writeCsv(ts + ",500,Tx,200,OK,t-1,text,true,512,128,450,200,30");

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.includeTimerDuration = true;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        double avg = result.results.get("Tx").getMean();
        assertEquals(500.0, avg, 1.0, "Avg response time should equal raw elapsed (500ms)");
    }

    @Test
    @DisplayName("includeTimerDuration=false — IdleTime subtracted from elapsed")
    void includeTimerDurationOffSubtractsIdleTime() throws IOException {
        long ts = System.currentTimeMillis();
        // elapsed=500, IdleTime=200 — with flag OFF, net elapsed = 500 - 200 = 300ms
        Path file = writeCsv(ts + ",500,Tx,200,OK,t-1,text,true,512,128,450,200,30");

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.includeTimerDuration = false;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        double avg = result.results.get("Tx").getMean();
        assertEquals(300.0, avg, 1.0, "Avg response time should be elapsed minus IdleTime (300ms)");
    }

    @Test
    @DisplayName("includeTimerDuration=false with zero IdleTime — elapsed unchanged")
    void includeTimerDurationOffWithZeroIdleTimeIsNoop() throws IOException {
        long ts = System.currentTimeMillis();
        // IdleTime=0 — subtracting nothing should leave elapsed intact
        Path file = writeCsv(ts + ",400,Tx,200,OK,t-1,text,true,512,128,380,0,20");

        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.includeTimerDuration = false;

        JTLParser.ParseResult result = new JTLParser().parse(file.toString(), opts);

        double avg = result.results.get("Tx").getMean();
        assertEquals(400.0, avg, 1.0, "Avg response time should be unchanged when IdleTime=0");
    }
}