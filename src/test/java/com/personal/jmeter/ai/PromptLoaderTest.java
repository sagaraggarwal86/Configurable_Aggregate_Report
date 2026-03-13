package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptLoader}.
 *
 * <p>No file system writes, no network, no Swing — verifies classpath resource
 * loading behaviour using the bundled {@code ai-reporter-prompt.txt} that is
 * present in the plugin JAR at test time.</p>
 */
@DisplayName("PromptLoader")
class PromptLoaderTest {

    // ─────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Resource path constants")
    class ConstantTests {

        @Test
        @DisplayName("PROMPT_FILE_NAME is ai-reporter-prompt.txt")
        void promptFileNameCorrect() {
            assertEquals("ai-reporter-prompt.txt", PromptLoader.PROMPT_FILE_NAME);
        }

        @Test
        @DisplayName("RESOURCE_PATH starts with /")
        void resourcePathStartsWithSlash() {
            assertTrue(PromptLoader.RESOURCE_PATH.startsWith("/"),
                    "RESOURCE_PATH must be an absolute classpath resource");
        }

        @Test
        @DisplayName("RESOURCE_PATH ends with PROMPT_FILE_NAME")
        void resourcePathEndsWithFileName() {
            assertTrue(PromptLoader.RESOURCE_PATH.endsWith(PromptLoader.PROMPT_FILE_NAME),
                    "RESOURCE_PATH must end with PROMPT_FILE_NAME");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // load() — bundled resource
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("load() — bundled resource")
    class LoadTests {

        @Test
        @DisplayName("load() returns non-null from bundled JAR resource")
        void loadReturnsNonNull() {
            String result = PromptLoader.load();
            assertNotNull(result,
                    "load() must return non-null — bundled ai-reporter-prompt.txt must be on classpath");
        }

        @Test
        @DisplayName("load() returns non-blank string")
        void loadReturnsNonBlank() {
            String result = PromptLoader.load();
            assertNotNull(result);
            assertFalse(result.isBlank(),
                    "load() must return non-blank content from bundled prompt");
        }

        @Test
        @DisplayName("load() returns same content on repeated calls")
        void loadIsIdempotent() {
            String first  = PromptLoader.load();
            String second = PromptLoader.load();
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(first, second,
                    "load() must return identical content on each call");
        }

        @Test
        @DisplayName("load() result does not start or end with whitespace (trimmed)")
        void loadResultIsTrimmed() {
            String result = PromptLoader.load();
            assertNotNull(result);
            assertEquals(result, result.strip(),
                    "load() result must be trimmed of leading/trailing whitespace");
        }
    }
}
