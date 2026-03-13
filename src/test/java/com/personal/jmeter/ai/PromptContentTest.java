package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptContent}.
 *
 * <p>No file system, no network, no Swing — pure record contract verification:
 * null rejection, value storage, and accessor correctness.</p>
 */
@DisplayName("PromptContent")
class PromptContentTest {

    // ─────────────────────────────────────────────────────────────
    // Null rejection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null rejection")
    class NullRejectionTests {

        @Test
        @DisplayName("null systemPrompt throws NullPointerException")
        void nullSystemPromptThrows() {
            assertThrows(NullPointerException.class,
                    () -> new PromptContent(null, "user message"));
        }

        @Test
        @DisplayName("null userMessage throws NullPointerException")
        void nullUserMessageThrows() {
            assertThrows(NullPointerException.class,
                    () -> new PromptContent("system prompt", null));
        }

        @Test
        @DisplayName("both null throws NullPointerException")
        void bothNullThrows() {
            assertThrows(NullPointerException.class,
                    () -> new PromptContent(null, null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Value storage and accessors
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Value storage and accessors")
    class ValueStorageTests {

        @Test
        @DisplayName("systemPrompt accessor returns the value passed to constructor")
        void systemPromptStoredCorrectly() {
            PromptContent content = new PromptContent("sys", "usr");
            assertEquals("sys", content.systemPrompt());
        }

        @Test
        @DisplayName("userMessage accessor returns the value passed to constructor")
        void userMessageStoredCorrectly() {
            PromptContent content = new PromptContent("sys", "usr");
            assertEquals("usr", content.userMessage());
        }

        @Test
        @DisplayName("empty string values are accepted and stored")
        void emptyStringsAccepted() {
            assertDoesNotThrow(() -> new PromptContent("", ""));
            PromptContent content = new PromptContent("", "");
            assertEquals("", content.systemPrompt());
            assertEquals("", content.userMessage());
        }

        @Test
        @DisplayName("multi-line values are stored intact")
        void multilineValuesStoredIntact() {
            String sys = "Line1\nLine2\nLine3";
            String usr = "Data:\n- item1\n- item2";
            PromptContent content = new PromptContent(sys, usr);
            assertEquals(sys, content.systemPrompt());
            assertEquals(usr, content.userMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Record equality
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("two instances with same values are equal")
        void sameValuesAreEqual() {
            PromptContent a = new PromptContent("system", "user");
            PromptContent b = new PromptContent("system", "user");
            assertEquals(a, b);
        }

        @Test
        @DisplayName("instances with different systemPrompt are not equal")
        void differentSystemPromptNotEqual() {
            PromptContent a = new PromptContent("sys-a", "user");
            PromptContent b = new PromptContent("sys-b", "user");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("instances with different userMessage are not equal")
        void differentUserMessageNotEqual() {
            PromptContent a = new PromptContent("system", "user-a");
            PromptContent b = new PromptContent("system", "user-b");
            assertNotEquals(a, b);
        }
    }
}
