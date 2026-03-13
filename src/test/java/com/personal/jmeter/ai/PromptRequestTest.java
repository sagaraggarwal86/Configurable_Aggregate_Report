package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptRequest}.
 *
 * <p>No file system, no network, no Swing — pure record contract verification:
 * null normalisation in the compact canonical constructor and the {@code empty()}
 * factory method.</p>
 */
@DisplayName("PromptRequest")
class PromptRequestTest {

    private static final String NOT_CONFIGURED = "Not configured";

    // ─────────────────────────────────────────────────────────────
    // Compact constructor — null normalisation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Compact constructor null normalisation")
    class NullNormalisationTests {

        @Test
        @DisplayName("null users normalised to empty string")
        void nullUsersNormalised() {
            PromptRequest r = new PromptRequest(
                    null, "name", "desc", "start", "end", "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.users());
        }

        @Test
        @DisplayName("null scenarioName normalised to empty string")
        void nullScenarioNameNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", null, "desc", "start", "end", "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.scenarioName());
        }

        @Test
        @DisplayName("null scenarioDesc normalised to empty string")
        void nullScenarioDescNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", null, "start", "end", "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.scenarioDesc());
        }

        @Test
        @DisplayName("null startTime normalised to empty string")
        void nullStartTimeNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", null, "end", "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.startTime());
        }

        @Test
        @DisplayName("null endTime normalised to empty string")
        void nullEndTimeNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", null, "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.endTime());
        }

        @Test
        @DisplayName("null duration normalised to empty string")
        void nullDurationNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", "end", null, "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.duration());
        }

        @Test
        @DisplayName("null threadGroupName normalised to empty string")
        void nullThreadGroupNameNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", "end", "dur", null,
                    90, NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals("", r.threadGroupName());
        }

        @Test
        @DisplayName("null errorSlaThresholdPct normalised to 'Not configured'")
        void nullErrorSlaNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", "end", "dur", "tg",
                    90, null, NOT_CONFIGURED, NOT_CONFIGURED);
            assertEquals(NOT_CONFIGURED, r.errorSlaThresholdPct());
        }

        @Test
        @DisplayName("null rtSlaThresholdMs normalised to 'Not configured'")
        void nullRtSlaNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", "end", "dur", "tg",
                    90, NOT_CONFIGURED, null, NOT_CONFIGURED);
            assertEquals(NOT_CONFIGURED, r.rtSlaThresholdMs());
        }

        @Test
        @DisplayName("null rtSlaMetric normalised to 'Not configured'")
        void nullRtMetricNormalised() {
            PromptRequest r = new PromptRequest(
                    "10", "name", "desc", "start", "end", "dur", "tg",
                    90, NOT_CONFIGURED, NOT_CONFIGURED, null);
            assertEquals(NOT_CONFIGURED, r.rtSlaMetric());
        }

        @Test
        @DisplayName("all null strings — no NullPointerException")
        void allNullsNoException() {
            assertDoesNotThrow(() -> new PromptRequest(
                    null, null, null, null, null, null, null,
                    90, null, null, null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Value preservation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Value preservation")
    class ValuePreservationTests {

        @Test
        @DisplayName("non-null values are stored as-is")
        void nonNullValuesStoredAsIs() {
            PromptRequest r = new PromptRequest(
                    "200", "Load Test", "Soak run", "10:00", "11:00", "60m", "Users",
                    95, "5%", "2000ms", "P95 (ms)");
            assertEquals("200",      r.users());
            assertEquals("Load Test", r.scenarioName());
            assertEquals("Soak run", r.scenarioDesc());
            assertEquals("10:00",    r.startTime());
            assertEquals("11:00",    r.endTime());
            assertEquals("60m",      r.duration());
            assertEquals("Users",    r.threadGroupName());
            assertEquals(95,         r.configuredPercentile());
            assertEquals("5%",       r.errorSlaThresholdPct());
            assertEquals("2000ms",   r.rtSlaThresholdMs());
            assertEquals("P95 (ms)", r.rtSlaMetric());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // empty() factory
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("empty() factory")
    class EmptyFactoryTests {

        @Test
        @DisplayName("empty() returns non-null instance")
        void emptyReturnsNonNull() {
            assertNotNull(PromptRequest.empty());
        }

        @Test
        @DisplayName("empty() string fields are empty strings")
        void emptyStringFieldsAreEmpty() {
            PromptRequest r = PromptRequest.empty();
            assertEquals("", r.users());
            assertEquals("", r.scenarioName());
            assertEquals("", r.scenarioDesc());
            assertEquals("", r.startTime());
            assertEquals("", r.endTime());
            assertEquals("", r.duration());
            assertEquals("", r.threadGroupName());
        }

        @Test
        @DisplayName("empty() configuredPercentile defaults to 90")
        void emptyPercentileDefaultsTo90() {
            assertEquals(90, PromptRequest.empty().configuredPercentile());
        }

        @Test
        @DisplayName("empty() SLA fields default to 'Not configured'")
        void emptySlaFieldsDefaultToNotConfigured() {
            PromptRequest r = PromptRequest.empty();
            assertEquals(NOT_CONFIGURED, r.errorSlaThresholdPct());
            assertEquals(NOT_CONFIGURED, r.rtSlaThresholdMs());
            assertEquals(NOT_CONFIGURED, r.rtSlaMetric());
        }
    }
}
