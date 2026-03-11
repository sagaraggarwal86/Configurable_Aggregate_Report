package com.personal.jmeter.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CliArgs}.
 *
 * <p>No file system side effects except where a real file path is required by
 * validation (uses {@link TempDir} for those cases). No network, no Swing.</p>
 */
@DisplayName("CliArgs")
class CliArgsTest {

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Writes a minimal temporary JTL file and returns its absolute path. */
    private static String tempJtl(Path dir) throws IOException {
        Path f = dir.resolve("test.jtl");
        Files.writeString(f, "timeStamp,elapsed,label\n");
        return f.toString();
    }

    /** Writes a minimal temporary properties file and returns its absolute path. */
    private static String tempConfig(Path dir) throws IOException {
        Path f = dir.resolve("ai-reporter.properties");
        Files.writeString(f, "ai.reporter.groq.api.key=gsk_test\n");
        return f.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Help flag
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Help flag")
    class HelpTests {

        @Test
        @DisplayName("--help sets helpRequested=true and skips validation")
        void helpFlagSkipsValidation() {
            CliArgs cli = CliArgs.parse(new String[]{"--help"});
            assertTrue(cli.helpRequested());
            assertTrue(cli.errors().isEmpty(), "No validation errors when --help is set");
        }

        @Test
        @DisplayName("-h is an alias for --help")
        void shortHelpAlias() {
            CliArgs cli = CliArgs.parse(new String[]{"-h"});
            assertTrue(cli.helpRequested());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Required arguments
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Required arguments")
    class RequiredArgTests {

        @Test
        @DisplayName("missing --input produces error")
        void missingInputProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--input")));
        }

        @Test
        @DisplayName("--ai is no longer required — omitting it produces no error")
        void omittingAiProducesNoError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--ai")),
                    "--ai should not be required");
        }

        @Test
        @DisplayName("missing --provider produces error")
        void missingProviderProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--provider")));
        }

        @Test
        @DisplayName("missing --config produces error")
        void missingConfigProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--config")));
        }

        @Test
        @DisplayName("non-existent input file produces error")
        void nonExistentInputFileProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", "/nonexistent/file.jtl",
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("not found")));
        }

        @Test
        @DisplayName("all required args present — no errors")
        void allRequiredArgsNoErrors(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().isEmpty(), "Expected no errors but got: " + cli.errors());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Default values
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("outputFile defaults to ./report.html")
        void outputFileDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals("./report.html", cli.outputFile());
        }

        @Test
        @DisplayName("percentile defaults to 90")
        void percentileDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals(90, cli.percentile());
        }

        @Test
        @DisplayName("startOffset defaults to 0")
        void startOffsetDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals(0, cli.startOffset());
        }

        @Test
        @DisplayName("regex defaults to false")
        void regexDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.regex());
        }

        @Test
        @DisplayName("hasErrorSla returns false when --error-sla not set")
        void hasErrorSlaFalseByDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir),
                    "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.hasErrorSla());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Range validation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("percentile=0 produces error")
        void percentileTooLow(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--percentile", "0"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("percentile=100 produces error")
        void percentileTooHigh(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--percentile", "100"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("percentile=95 is accepted")
        void percentileValid(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--percentile", "95"
            });
            assertEquals(95, cli.percentile());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("error-sla=100 produces error (must be 1–99)")
        void errorSlaOutOfRange(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--error-sla", "100"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
        }

        @Test
        @DisplayName("chart-interval=3601 produces error")
        void chartIntervalTooHigh(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--chart-interval", "3601"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--chart-interval")));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Parsing behaviour
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parsing behaviour")
    class ParsingTests {

        @Test
        @DisplayName("provider name is normalised to lowercase")
        void providerNormalisedToLowercase(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "GROQ",
                    "--config", tempConfig(dir)
            });
            assertEquals("groq", cli.provider());
        }

        @Test
        @DisplayName("--regex without --search produces error")
        void regexWithoutSearchProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--regex"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--regex")));
        }

        @Test
        @DisplayName("--regex with --search is accepted")
        void regexWithSearchAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--search", "Login", "--regex"
            });
            assertTrue(cli.regex());
            assertEquals("Login", cli.search());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--regex")));
        }

        @Test
        @DisplayName("unknown argument produces error")
        void unknownArgumentProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--unknown-flag"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("Unknown argument")));
        }

        @Test
        @DisplayName("non-integer value for --percentile produces error")
        void nonIntegerPercentileProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--percentile", "abc"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("--rt-metric avg is accepted and normalised to lowercase")
        void rtMetricAvgAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--rt-metric", "AVG"
            });
            assertEquals("avg", cli.rtMetric());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--rt-metric")));
        }

        @Test
        @DisplayName("--rt-metric invalid value produces error")
        void rtMetricInvalidProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir), "--rt-metric", "median"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--rt-metric")));
        }

        @Test
        @DisplayName("--virtual-users and --scenario-name are parsed correctly")
        void metadataArgsParsedCorrectly(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                    "--input", tempJtl(dir), "--provider", "groq",
                    "--config", tempConfig(dir),
                    "--virtual-users", "200", "--scenario-name", "Load Test"
            });
            assertEquals(200, cli.virtualUsers());
            assertEquals("Load Test", cli.scenarioName());
        }
    }
}
