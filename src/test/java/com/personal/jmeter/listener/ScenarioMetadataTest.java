package com.personal.jmeter.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScenarioMetadata}.
 *
 * <p>No file system, no network, no Swing — pure value object verification.</p>
 */
@DisplayName("ScenarioMetadata")
class ScenarioMetadataTest {

    // ─────────────────────────────────────────────────────────────
    // Constructor — null normalisation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constructor null normalisation")
    class NullNormalisationTests {

        @Test
        @DisplayName("null scenarioName normalised to empty string")
        void nullScenarioNameNormalisedToEmpty() {
            ScenarioMetadata m = new ScenarioMetadata(null, "desc", "10", "tg");
            assertEquals("", m.scenarioName);
        }

        @Test
        @DisplayName("null scenarioDesc normalised to empty string")
        void nullScenarioDescNormalisedToEmpty() {
            ScenarioMetadata m = new ScenarioMetadata("name", null, "10", "tg");
            assertEquals("", m.scenarioDesc);
        }

        @Test
        @DisplayName("null users normalised to empty string")
        void nullUsersNormalisedToEmpty() {
            ScenarioMetadata m = new ScenarioMetadata("name", "desc", null, "tg");
            assertEquals("", m.users);
        }

        @Test
        @DisplayName("null threadGroupName normalised to empty string")
        void nullThreadGroupNameNormalisedToEmpty() {
            ScenarioMetadata m = new ScenarioMetadata("name", "desc", "10", null);
            assertEquals("", m.threadGroupName);
        }

        @Test
        @DisplayName("all nulls normalised — no NullPointerException")
        void allNullsNoException() {
            assertDoesNotThrow(() -> new ScenarioMetadata(null, null, null, null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Constructor — value preservation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constructor value preservation")
    class ValuePreservationTests {

        @Test
        @DisplayName("non-null values are stored as-is")
        void nonNullValuesStoredAsIs() {
            ScenarioMetadata m = new ScenarioMetadata(
                    "Load Test", "Soak run", "200", "Users Thread Group");
            assertEquals("Load Test",          m.scenarioName);
            assertEquals("Soak run",           m.scenarioDesc);
            assertEquals("200",                m.users);
            assertEquals("Users Thread Group", m.threadGroupName);
        }

        @Test
        @DisplayName("empty string values are stored unchanged")
        void emptyStringsStoredUnchanged() {
            ScenarioMetadata m = new ScenarioMetadata("", "", "", "");
            assertEquals("", m.scenarioName);
            assertEquals("", m.scenarioDesc);
            assertEquals("", m.users);
            assertEquals("", m.threadGroupName);
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
            assertNotNull(ScenarioMetadata.empty());
        }

        @Test
        @DisplayName("empty() scenarioName is empty string")
        void emptyScenarioName() {
            assertEquals("", ScenarioMetadata.empty().scenarioName);
        }

        @Test
        @DisplayName("empty() scenarioDesc is empty string")
        void emptyScenarioDesc() {
            assertEquals("", ScenarioMetadata.empty().scenarioDesc);
        }

        @Test
        @DisplayName("empty() users is empty string")
        void emptyUsers() {
            assertEquals("", ScenarioMetadata.empty().users);
        }

        @Test
        @DisplayName("empty() threadGroupName is empty string")
        void emptyThreadGroupName() {
            assertEquals("", ScenarioMetadata.empty().threadGroupName);
        }

        @Test
        @DisplayName("two empty() calls return equivalent instances")
        void twoEmptyCallsAreEquivalent() {
            ScenarioMetadata a = ScenarioMetadata.empty();
            ScenarioMetadata b = ScenarioMetadata.empty();
            assertEquals(a.scenarioName,    b.scenarioName);
            assertEquals(a.scenarioDesc,    b.scenarioDesc);
            assertEquals(a.users,           b.users);
            assertEquals(a.threadGroupName, b.threadGroupName);
        }
    }
}
