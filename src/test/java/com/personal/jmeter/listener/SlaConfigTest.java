package com.personal.jmeter.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SlaConfig}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * both factory methods and all state-query methods.</p>
 */
@DisplayName("SlaConfig")
class SlaConfigTest {

    // ─────────────────────────────────────────────────────────────
    // disabled() factory
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("disabled() factory")
    class DisabledTests {

        @Test
        @DisplayName("isErrorPctEnabled() returns false")
        void errorPctDisabled() {
            assertFalse(SlaConfig.disabled(90).isErrorPctEnabled());
        }

        @Test
        @DisplayName("isRtEnabled() returns false")
        void rtDisabled() {
            assertFalse(SlaConfig.disabled(90).isRtEnabled());
        }

        @Test
        @DisplayName("percentile is preserved")
        void percentilePreserved() {
            assertEquals(95, SlaConfig.disabled(95).percentile);
        }

        @Test
        @DisplayName("errorPctThreshold sentinel is -1")
        void errorPctSentinel() {
            assertEquals(-1.0, SlaConfig.disabled(90).errorPctThreshold);
        }

        @Test
        @DisplayName("rtThresholdMs sentinel is -1")
        void rtThresholdSentinel() {
            assertEquals(-1L, SlaConfig.disabled(90).rtThresholdMs);
        }

        @Test
        @DisplayName("rtMetric defaults to PNN")
        void rtMetricDefaultsPnn() {
            assertEquals(SlaConfig.RtMetric.PNN, SlaConfig.disabled(90).rtMetric);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // from() factory — both thresholds enabled
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("from() factory — both thresholds enabled")
    class FromBothEnabledTests {

        private final SlaConfig config = SlaConfig.from("5", "2000",
                SlaConfig.RtMetric.PNN, 90);

        @Test
        @DisplayName("isErrorPctEnabled() returns true")
        void errorPctEnabled() {
            assertTrue(config.isErrorPctEnabled());
        }

        @Test
        @DisplayName("errorPctThreshold is parsed correctly")
        void errorPctValue() {
            assertEquals(5.0, config.errorPctThreshold);
        }

        @Test
        @DisplayName("isRtEnabled() returns true")
        void rtEnabled() {
            assertTrue(config.isRtEnabled());
        }

        @Test
        @DisplayName("rtThresholdMs is parsed correctly")
        void rtThresholdValue() {
            assertEquals(2000L, config.rtThresholdMs);
        }

        @Test
        @DisplayName("rtMetric is PNN")
        void rtMetricPnn() {
            assertEquals(SlaConfig.RtMetric.PNN, config.rtMetric);
        }

        @Test
        @DisplayName("percentile is preserved")
        void percentilePreserved() {
            assertEquals(90, config.percentile);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // from() factory — blank strings disable thresholds
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("from() factory — blank strings disable thresholds")
    class FromBlankTests {

        @Test
        @DisplayName("blank errorPctStr disables error threshold")
        void blankErrorPctDisabled() {
            SlaConfig cfg = SlaConfig.from("", "1000", SlaConfig.RtMetric.AVG, 90);
            assertFalse(cfg.isErrorPctEnabled());
        }

        @Test
        @DisplayName("blank rtThresholdStr disables RT threshold")
        void blankRtDisabled() {
            SlaConfig cfg = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
            assertFalse(cfg.isRtEnabled());
        }

        @Test
        @DisplayName("null errorPctStr disables error threshold")
        void nullErrorPctDisabled() {
            SlaConfig cfg = SlaConfig.from(null, "1000", SlaConfig.RtMetric.AVG, 90);
            assertFalse(cfg.isErrorPctEnabled());
        }

        @Test
        @DisplayName("null rtThresholdStr disables RT threshold")
        void nullRtDisabled() {
            SlaConfig cfg = SlaConfig.from("5", null, SlaConfig.RtMetric.PNN, 90);
            assertFalse(cfg.isRtEnabled());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // from() factory — AVG metric
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("from() factory — AVG metric")
    class FromAvgMetricTests {

        @Test
        @DisplayName("rtMetric AVG is stored correctly")
        void rtMetricAvgStored() {
            SlaConfig cfg = SlaConfig.from("", "500", SlaConfig.RtMetric.AVG, 90);
            assertEquals(SlaConfig.RtMetric.AVG, cfg.rtMetric);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // from() factory — null rtMetric defaults to PNN
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("from() factory — null rtMetric")
    class FromNullMetricTests {

        @Test
        @DisplayName("null rtMetric defaults to PNN")
        void nullRtMetricDefaultsPnn() {
            SlaConfig cfg = SlaConfig.from("5", "1000", null, 90);
            assertEquals(SlaConfig.RtMetric.PNN, cfg.rtMetric);
        }
    }
}
