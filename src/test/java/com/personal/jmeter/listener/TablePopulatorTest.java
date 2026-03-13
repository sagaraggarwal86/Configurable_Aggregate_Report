package com.personal.jmeter.listener;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TablePopulator#buildRowAsStrings}.
 *
 * <p>No Swing, no file system, no network — pure in-memory verification of
 * the static formatting method that is the single source of truth for row
 * data in both the GUI table and the CLI HTML report.</p>
 */
@DisplayName("TablePopulator — buildRowAsStrings")
class TablePopulatorTest {

    @BeforeAll
    static void initJMeter() {
        URL propsUrl = TablePopulatorTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static SamplingStatCalculator calcWithSamples(String label,
                                                           long[] elapsedMs,
                                                           boolean[] success) {
        SamplingStatCalculator calc = new SamplingStatCalculator(label);
        long ts = System.currentTimeMillis();
        for (int i = 0; i < elapsedMs.length; i++) {
            SampleResult sr = new SampleResult();
            sr.setStampAndTime(ts + i * 100L, elapsedMs[i]);
            sr.setSuccessful(success[i]);
            calc.addSample(sr);
        }
        return calc;
    }

    private static SamplingStatCalculator allPassed(String label, long... elapsedMs) {
        boolean[] success = new boolean[elapsedMs.length];
        for (int i = 0; i < success.length; i++) success[i] = true;
        return calcWithSamples(label, elapsedMs, success);
    }

    // ─────────────────────────────────────────────────────────────
    // Row structure
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Row structure")
    class RowStructureTests {

        @Test
        @DisplayName("returns exactly 11 elements")
        void returns11Elements() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 300L), 0.90);
            assertEquals(11, row.length);
        }

        @Test
        @DisplayName("first element is the label")
        void firstElementIsLabel() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Checkout", 300L), 0.90);
            assertEquals("Checkout", row[0]);
        }

        @Test
        @DisplayName("no element is null")
        void noElementIsNull() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 300L), 0.90);
            for (int i = 0; i < row.length; i++) {
                assertNotNull(row[i], "Element at index " + i + " must not be null");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Count, passed, failed columns
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Count, passed and failed columns")
    class CountTests {

        @Test
        @DisplayName("total count matches number of samples added")
        void totalCountCorrect() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 100L, 200L, 300L), 0.90);
            assertEquals("3", row[1]);
        }

        @Test
        @DisplayName("passed count equals total when no failures")
        void passedCountAllPassed() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 100L, 200L), 0.90);
            assertEquals("2", row[2]); // passed
            assertEquals("0", row[3]); // failed
        }

        @Test
        @DisplayName("failed count reflects error samples")
        void failedCountReflectsErrors() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L, 300L},
                    new boolean[]{true, false, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertEquals("3", row[1]); // total
            assertEquals("1", row[2]); // passed
            assertEquals("2", row[3]); // failed
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Response time columns
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response time columns")
    class ResponseTimeTests {

        @Test
        @DisplayName("avg (mean) is integer-rounded")
        void avgIsIntegerRounded() {
            // Two samples: 100ms and 200ms → avg = 150ms
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L), 0.90);
            assertEquals("150", row[4]);
        }

        @Test
        @DisplayName("min is the smallest elapsed")
        void minIsSmallest() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 300L, 500L), 0.90);
            assertEquals("100", row[5]);
        }

        @Test
        @DisplayName("max is the largest elapsed")
        void maxIsLargest() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 300L, 500L), 0.90);
            assertEquals("500", row[6]);
        }

        @Test
        @DisplayName("percentile column index is 7")
        void percentileColumnIndex() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L, 300L), 0.90);
            assertNotNull(row[7], "Percentile value must not be null");
            assertFalse(row[7].isBlank(), "Percentile value must not be blank");
        }

        @Test
        @DisplayName("std deviation has one decimal place")
        void stdDevOneDecimalPlace() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L, 300L), 0.90);
            assertTrue(row[8].matches("\\d+\\.\\d"),
                    "Std deviation must match pattern 'N.N', got: " + row[8]);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error rate and throughput columns
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error rate and throughput columns")
    class ErrorAndThroughputTests {

        @Test
        @DisplayName("error rate is 0.00% when no failures")
        void errorRateZeroWhenNoFailures() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 200L, 300L), 0.90);
            assertEquals("0.00%", row[9]);
        }

        @Test
        @DisplayName("error rate ends with percent sign")
        void errorRateEndsWithPercent() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L},
                    new boolean[]{true, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertTrue(row[9].endsWith("%"), "Error rate must end with '%', got: " + row[9]);
        }

        @Test
        @DisplayName("throughput ends with /sec")
        void throughputEndsWithPerSec() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 200L, 300L), 0.90);
            assertTrue(row[10].endsWith("/sec"),
                    "Throughput must end with '/sec', got: " + row[10]);
        }

        @Test
        @DisplayName("100% error rate when all samples fail")
        void fullErrorRate() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L},
                    new boolean[]{false, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertEquals("100.00%", row[9]);
            assertEquals("0", row[2]); // passed = 0
            assertEquals("2", row[3]); // failed = 2
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Percentile fraction variations
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Percentile fraction variations")
    class PercentileFractionTests {

        @Test
        @DisplayName("p50 and p90 both produce non-null non-blank values")
        void differentPercentilesNonNull() {
            SamplingStatCalculator calc = allPassed("Tx",
                    100L, 150L, 200L, 250L, 300L, 350L, 400L, 450L, 500L, 600L);
            String[] rowP50 = TablePopulator.buildRowAsStrings(calc, 0.50);
            String[] rowP90 = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertFalse(rowP50[7].isBlank());
            assertFalse(rowP90[7].isBlank());
        }
    }
}
