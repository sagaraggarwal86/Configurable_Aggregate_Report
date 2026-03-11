package com.personal.jmeter.cli;

import com.personal.jmeter.ai.*;
import com.personal.jmeter.listener.TransactionFilter;
import com.personal.jmeter.listener.TablePopulator;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Headless pipeline: parse JTL → build prompt → call AI → render HTML.
 *
 * <p>Reuses the project's existing parser, prompt builder, AI service,
 * and renderer without any Swing dependency. Progress messages are written
 * to {@code System.err} so {@code stdout} stays clean for piping.</p>
 */
final class CliReportPipeline {

    private static final String TOTAL_LABEL = "TOTAL";

    private final CliArgs args;
    private final PrintStream progress;

    CliReportPipeline(CliArgs args) {
        this.args     = args;
        this.progress = System.err;
    }

    // ─────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────

    /**
     * Executes the full pipeline.
     *
     * @return the absolute path of the generated HTML report
     * @throws IOException on parse, AI, or write failure
     */
    String execute() throws IOException {

        // Step 1 — Parse JTL
        progress("Parsing JTL file: " + args.inputFile());
        JTLParser.FilterOptions opts = buildFilterOptions();
        JTLParser.ParseResult result = new JTLParser().parse(args.inputFile(), opts);
        progress("Parsed %d transaction types, %d total samples.",
                Math.max(0, result.results.size() - 1),
                result.results.containsKey(TOTAL_LABEL)
                        ? result.results.get(TOTAL_LABEL).getCount() : 0);

        // Step 2 — Build table rows
        List<String[]> tableRows = buildTableRows(result, opts.percentile);
        progress("Built %d table rows.", tableRows.size());

        // Step 3 — Resolve shared time/user context (used by both prompt and render config)
        TimeContext timeCtx = new TimeContext(
                result.formattedStartTime(),
                result.formattedEndTime(),
                result.formattedDuration(),
                args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "");

        // Step 3 — Resolve AI provider
        progress("Loading provider configuration from: " + args.configFile());
        AiProviderConfig provider = resolveProvider();
        progress("Provider: %s (model: %s)", provider.displayName, provider.model);

        // Step 4 — Validate and ping
        progress("Validating API key and pinging %s...", provider.displayName);
        String pingError = AiProviderRegistry.validateAndPing(provider);
        if (pingError != null) {
            throw new AiProviderException("Provider validation failed for " + provider.displayName
                    + ":\n" + pingError);
        }
        progress("Ping successful.");

        // Step 5 — Load prompt and build content
        progress("Building analysis prompt...");
        String systemPrompt = PromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Bundled prompt resource not found. Plugin JAR may be corrupt.");
        }
        PromptContent prompt = buildPromptContent(result, systemPrompt, timeCtx);

        // Step 6 — Call AI
        progress("Calling %s (this may take 30-60 seconds)...", provider.displayName);
        AiReportService service = new AiReportService(provider);
        String markdown = service.generateReport(prompt);
        progress("AI response received (%d characters).", markdown.length());

        // Step 7 — Render HTML
        progress("Rendering HTML report...");
        HtmlReportRenderer.RenderConfig config = buildRenderConfig(result, timeCtx);
        String outputPath = new HtmlReportRenderer().renderToFile(
                markdown, args.outputFile(), config, tableRows, result.timeBuckets);
        progress("Report saved to: " + outputPath);

        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────
    // Filter options
    // ─────────────────────────────────────────────────────────────

    private JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset         = args.startOffset();
        opts.endOffset           = args.endOffset();
        opts.percentile          = args.percentile();
        opts.chartIntervalSeconds = args.chartInterval();
        return opts;
    }

    // ─────────────────────────────────────────────────────────────
    // Table row building (delegates to TablePopulator — single source of truth)
    // ─────────────────────────────────────────────────────────────

    private List<String[]> buildTableRows(JTLParser.ParseResult result, int percentile) {
        double pFraction = percentile / 100.0;
        List<String[]> rows = new ArrayList<>();

        for (SamplingStatCalculator calc : result.results.values()) {
            if (calc.getCount() == 0) continue;
            String label = calc.getLabel();
            if (TOTAL_LABEL.equals(label)) continue;

            // Apply search filter if specified
            if (!args.search().isBlank()
                    && !TransactionFilter.matches(label, args.search(), args.regex())) {
                continue;
            }

            rows.add(TablePopulator.buildRowAsStrings(calc, pFraction));
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────────────
    // Provider resolution
    // ─────────────────────────────────────────────────────────────

    private AiProviderConfig resolveProvider() throws IOException {
        List<AiProviderConfig> providers =
                AiProviderRegistry.loadConfiguredProviders(Path.of(args.configFile()));

        return providers.stream()
                .filter(p -> p.providerKey.equalsIgnoreCase(args.provider()))
                .findFirst()
                .orElseThrow(() -> new AiProviderException(
                        "Provider '" + args.provider() + "' not found in " + args.configFile()
                                + ".\nConfigured providers: "
                                + (providers.isEmpty() ? "(none)"
                                : String.join(", ", providers.stream()
                                .map(p -> p.providerKey).toList()))));
    }

    // ─────────────────────────────────────────────────────────────
    // Prompt content
    // ─────────────────────────────────────────────────────────────

    private PromptContent buildPromptContent(JTLParser.ParseResult result,
                                             String systemPrompt,
                                             TimeContext timeCtx) {
        String slaErrorPct = args.hasErrorSla()
                ? args.errorSla() + "%" : "Not configured";
        String slaRtMs = args.hasRtSla()
                ? args.rtSla() + "ms" : "Not configured";
        String slaRtMetric = "percentile".equals(args.rtMetric())
                ? "P" + args.percentile() + " (ms)" : "Avg (ms)";

        PromptRequest request = new PromptRequest(
                timeCtx.users(),
                args.scenarioName(),
                args.description(),
                timeCtx.startTime(),
                timeCtx.endTime(),
                timeCtx.duration(),
                "",  // threadGroupName — not available from CLI
                args.percentile(),
                slaErrorPct,
                slaRtMs,
                slaRtMetric);

        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
                result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);

        return new PromptBuilder(systemPrompt)
                .build(result.results, args.percentile(), request,
                        result.errorTypeSummary, latency);
    }

    // ─────────────────────────────────────────────────────────────
    // Render config
    // ─────────────────────────────────────────────────────────────

    private HtmlReportRenderer.RenderConfig buildRenderConfig(JTLParser.ParseResult result,
                                                              TimeContext timeCtx) {
        return new HtmlReportRenderer.RenderConfig(
                timeCtx.users(),
                args.scenarioName(),
                args.description(),
                "",  // threadGroupName
                timeCtx.startTime(),
                timeCtx.endTime(),
                timeCtx.duration(),
                args.percentile());
    }

    // ─────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────

    private void progress(String format, Object... params) {
        progress.println("[CLI] " + String.format(format, params));
    }

    // ─────────────────────────────────────────────────────────────
    // Value types
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of formatted time and user-count values shared between
     * {@link #buildPromptContent} and {@link #buildRenderConfig}.
     * Computed once in {@link #execute()} to avoid duplicating the same four
     * derivations across two methods.
     */
    private record TimeContext(String startTime, String endTime,
                               String duration,  String users) {}
}
