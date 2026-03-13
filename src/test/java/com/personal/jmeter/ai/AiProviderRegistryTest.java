package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiProviderRegistry}.
 *
 * <p>No network, no file-system side effects, no Swing — tests exercise the
 * static logic: key-prefix validation, property loading, provider ordering,
 * integer/double fallback parsing, and buildConfig null-model guard.</p>
 */
@DisplayName("AiProviderRegistry")
class AiProviderRegistryTest {

    // ─────────────────────────────────────────────────────────────
    // checkKeyFormat
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkKeyFormat")
    class KeyFormatTests {

        @Test
        @DisplayName("valid Groq key prefix returns null")
        void validGroqPrefix() {
            AiProviderConfig config = new AiProviderConfig(
                    "groq", "Groq (Free)", "gsk_abc123", "llama-3.3-70b-versatile",
                    "https://api.groq.com/openai/v1", 60, 4096, 0.3);
            assertNull(AiProviderRegistry.checkKeyFormat(config));
        }

        @Test
        @DisplayName("invalid Groq key prefix returns error message")
        void invalidGroqPrefix() {
            AiProviderConfig config = new AiProviderConfig(
                    "groq", "Groq (Free)", "sk-wrong-prefix", "llama-3.3-70b-versatile",
                    "https://api.groq.com/openai/v1", 60, 4096, 0.3);
            String error = AiProviderRegistry.checkKeyFormat(config);
            assertNotNull(error, "Should return an error for wrong prefix");
            assertTrue(error.contains("gsk_"), "Error should mention expected prefix");
        }

        @Test
        @DisplayName("valid OpenAI key prefix returns null")
        void validOpenAiPrefix() {
            AiProviderConfig config = new AiProviderConfig(
                    "openai", "OpenAI (Paid)", "sk-proj123abc", "gpt-4o",
                    "https://api.openai.com/v1", 60, 4096, 0.3);
            assertNull(AiProviderRegistry.checkKeyFormat(config));
        }

        @Test
        @DisplayName("unknown provider — no format rule, returns null")
        void unknownProviderNoFormatRule() {
            AiProviderConfig config = new AiProviderConfig(
                    "custom-llm", "Custom LLM", "anything-goes", "my-model",
                    "https://my.llm/v1", 60, 4096, 0.3);
            assertNull(AiProviderRegistry.checkKeyFormat(config));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // loadProperties
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadProperties")
    class LoadPropertiesTests {

        @Test
        @DisplayName("null jmeterHome falls back to JAR resource without throwing")
        void nullJmeterHomeFallsBack() {
            Properties props = AiProviderRegistry.loadProperties(null);
            assertNotNull(props, "Should return Properties even with null jmeterHome");
        }

        @Test
        @DisplayName("loads properties from disk when file exists")
        void loadsFromDisk(@TempDir Path dir) throws IOException {
            Path binDir = dir.resolve("bin");
            Files.createDirectories(binDir);
            Files.writeString(binDir.resolve("ai-reporter.properties"),
                    "ai.reporter.testprovider.api.key=test-key-123\n"
                            + "ai.reporter.testprovider.model=test-model\n"
                            + "ai.reporter.testprovider.base.url=https://test.api/v1\n",
                    StandardCharsets.UTF_8);

            Properties props = AiProviderRegistry.loadProperties(dir.toFile());
            assertEquals("test-key-123", props.getProperty("ai.reporter.testprovider.api.key"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Provider ordering and buildConfig
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadConfiguredProviders ordering")
    class OrderingTests {

        @Test
        @DisplayName("known providers appear first in canonical order, unknowns alphabetically after")
        void knownProvidersFirstThenUnknown(@TempDir Path dir) throws IOException {
            Path binDir = dir.resolve("bin");
            Files.createDirectories(binDir);
            // Configure three providers: one unknown (zebra), one known (openai), one known (groq)
            Files.writeString(binDir.resolve("ai-reporter.properties"),
                    "ai.reporter.zebra.api.key=zkey\n"
                            + "ai.reporter.zebra.model=z-model\n"
                            + "ai.reporter.zebra.base.url=https://zebra/v1\n"
                            + "ai.reporter.openai.api.key=sk-oaikey\n"
                            + "ai.reporter.groq.api.key=gsk_groqkey\n",
                    StandardCharsets.UTF_8);

            List<AiProviderConfig> providers =
                    AiProviderRegistry.loadConfiguredProviders(dir.toFile());

            assertEquals(3, providers.size(), "Should discover 3 configured providers");
            // Known order: groq before openai (canonical list), then zebra (unknown, alphabetical)
            assertEquals("groq",   providers.get(0).providerKey);
            assertEquals("openai", providers.get(1).providerKey);
            assertEquals("zebra",  providers.get(2).providerKey);
        }

        @Test
        @DisplayName("provider with blank api.key is excluded")
        void blankApiKeyExcluded(@TempDir Path dir) throws IOException {
            Path binDir = dir.resolve("bin");
            Files.createDirectories(binDir);
            Files.writeString(binDir.resolve("ai-reporter.properties"),
                    "ai.reporter.groq.api.key=\n", StandardCharsets.UTF_8);

            List<AiProviderConfig> providers =
                    AiProviderRegistry.loadConfiguredProviders(dir.toFile());

            assertTrue(providers.isEmpty(), "Blank api.key should not produce a provider");
        }

        @Test
        @DisplayName("unknown provider with missing model and base.url is skipped")
        void unknownMissingModelSkipped(@TempDir Path dir) throws IOException {
            Path binDir = dir.resolve("bin");
            Files.createDirectories(binDir);
            // Unknown provider with key but no model/base.url — buildConfig returns null
            Files.writeString(binDir.resolve("ai-reporter.properties"),
                    "ai.reporter.niche.api.key=some-key\n", StandardCharsets.UTF_8);

            List<AiProviderConfig> providers =
                    AiProviderRegistry.loadConfiguredProviders(dir.toFile());

            assertTrue(providers.isEmpty(),
                    "Unknown provider without model or base.url should be skipped");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Ping cache
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ping cache eviction")
    class PingCacheTests {

        @Test
        @DisplayName("evictPingCache does not throw for a config that was never cached")
        void evictNonExistentKeyNoThrow() {
            AiProviderConfig config = new AiProviderConfig(
                    "nonexistent", "Nonexistent", "key-never-cached", "some-model",
                    "https://nonexistent.example/v1", 60, 4096, 0.3);
            assertDoesNotThrow(() -> AiProviderRegistry.evictPingCache(config),
                    "evictPingCache should not throw for a config that was never cached");
        }
    }
}