package com.personal.jmeter.cli;

import org.apache.jmeter.util.JMeterUtils;

import java.io.IOException;
import java.net.URL;

import com.personal.jmeter.ai.AiProviderException;
import com.personal.jmeter.ai.AiServiceException;
import com.personal.jmeter.parser.JtlParseException;

/**
 * Command-line entry point for the Configurable Aggregate Report plugin.
 *
 * <p>Parses a JTL file, calls the configured AI provider, and generates a
 * standalone HTML performance report — no JMeter GUI or Swing required.</p>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — success</li>
 *   <li>{@code 1} — invalid arguments</li>
 *   <li>{@code 2} — JTL parse error</li>
 *   <li>{@code 3} — AI provider error (key invalid, ping failed, API error)</li>
 *   <li>{@code 4} — report write error</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 * java -cp Configurable_Aggregate_Report.jar com.personal.jmeter.cli.Main \
 *   -i results.jtl --ai --provider groq --config ai-reporter.properties
 * </pre>
 */
public final class Main {

    /** Exit code: success. */
    static final int EXIT_OK             = 0;
    /** Exit code: invalid command-line arguments. */
    static final int EXIT_BAD_ARGS       = 1;
    /** Exit code: JTL parse failure. */
    static final int EXIT_PARSE_ERROR    = 2;
    /** Exit code: AI provider error (key, ping, or API). */
    static final int EXIT_AI_ERROR       = 3;
    /** Exit code: report file write failure. */
    static final int EXIT_WRITE_ERROR    = 4;

    private Main() {}

    /**
     * CLI entry point.
     *
     * @param args command-line arguments (see {@link CliArgs#helpText()})
     */
    public static void main(String[] args) {
        // Initialise JMeter properties (SampleResult needs them)
        initJMeterProperties();

        CliArgs cli = CliArgs.parse(args);

        if (cli.helpRequested()) {
            System.out.println(CliArgs.helpText());
            System.exit(EXIT_OK);
            return;
        }

        if (!cli.errors().isEmpty()) {
            System.err.println("Error(s):");
            cli.errors().forEach(e -> System.err.println("  " + e));
            System.err.println();
            System.err.println("Run with --help for usage.");
            System.exit(EXIT_BAD_ARGS);
            return;
        }

        try {
            String outputPath = new CliReportPipeline(cli).execute();
            System.out.println(outputPath);
            System.exit(EXIT_OK);
        } catch (JtlParseException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_PARSE_ERROR);
        } catch (AiProviderException | AiServiceException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_AI_ERROR);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_WRITE_ERROR);
        }
    }

    /**
     * Loads JMeter properties from the classpath so that {@code SampleResult}'s
     * constructor has the five properties it reads at instantiation time.
     * Falls back silently if not found — SampleResult uses its own defaults.
     */
    private static void initJMeterProperties() {
        URL propsUrl = Main.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }
}
