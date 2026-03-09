package com.personal.jmeter.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds the AI analysis prompt from already-aggregated JMeter results.
 *
 * <p><b>Summary-first strategy:</b> the AI receives global (TOTAL-row) statistics
 * and only the transactions that breach a threshold. Full per-transaction detail
 * is rendered in the separate "Transaction Metrics" table — the AI is not asked
 * to narrate every row.</p>
 *
 * <h3>JSON sections sent to the AI</h3>
 * <ul>
 *   <li>{@code globalStats}         — TOTAL-row KPIs</li>
 *   <li>{@code anomalyTransactions} — transactions that breach at least one threshold</li>
 *   <li>{@code errorEndpoints}      — transactions with any errors</li>
 *   <li>{@code slowestEndpoints}    — top-5 by Nth-percentile (label + value only)</li>
 * </ul>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TOTAL_LABEL = "TOTAL";
    private static final double MEDIAN = 0.50;

    // ── Anomaly thresholds ───────────────────────────────────────
    private static final double THRESHOLD_AVG_MS = 2_000.0;
    private static final double THRESHOLD_PCT_MS = 3_000.0;
    private static final double THRESHOLD_ERROR_PCT = 1.0;
    private static final double THRESHOLD_STD_DEV_RATIO = 0.5;

    /**
     * Prefix prepended to every generated prompt.
     */
    private static final String PROMPT_PREFIX =
            "You are a senior performance engineer specialising in bottleneck analysis and "
                    + "web diagnostics. Analyse the JMeter load test results below and write a concise "
                    + "professional report. Where response-time trends would normally be visible in a "
                    + "performance chart (e.g. ramp-up latency spikes, throughput plateaus, sustained "
                    + "degradation), infer those patterns from the statistical data provided "
                    + "(avg, median, stdDev, min/max). Apply web-performance diagnostic reasoning: "
                    + "consider DNS/TCP/TLS overhead, connection pool exhaustion, backend processing "
                    + "time, and network bandwidth saturation as candidate root causes when interpreting "
                    + "slow or variable endpoints.\n\n";

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ─────────────────────────────────────────────────────────────
    // JSON summary
    // ─────────────────────────────────────────────────────────────

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Safely extracts a {@code double} from a map value without unchecked casts.
     * Returns {@code 0.0} when the value is absent or not a {@link Number}.
     *
     * @param value map value to convert
     * @return numeric value, or 0.0
     */
    private static double asDouble(Object value) {
        return (value instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /**
     * Builds the AI analysis prompt from aggregated JMeter results.
     *
     * @param results    per-label aggregated statistics; must not be null
     * @param percentile percentile to report (1–99)
     * @param request    scenario context (users, name, description, timing); must not be null
     * @return fully assembled prompt string suitable for the Groq API
     */
    public String build(Map<String, SamplingStatCalculator> results,
                        int percentile,
                        PromptRequest request) {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(request, "request must not be null");
        log.info("build: building prompt. labels={}, percentile={}", results.size(), percentile);

        String json = GSON.toJson(buildSummary(results, percentile));
        String context = buildContextBlock(request);

        return PROMPT_PREFIX
                + "## Test Context\n" + context + "\n\n"
                + "## Test Data Summary (JSON)\n" + json + "\n\n"
                + buildReportStructureInstructions(percentile);
    }

    private Map<String, Object> buildSummary(Map<String, SamplingStatCalculator> results,
                                             int percentile) {
        final double pFraction = percentile / 100.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("globalStats", buildGlobalStats(results, percentile, pFraction));
        summary.put("anomalyTransactions", buildAnomalyTransactions(results, percentile, pFraction));
        summary.put("errorEndpoints", buildErrorEndpointList(results));
        summary.put("slowestEndpoints", buildSlowestList(results, pFraction, 5));
        return summary;
    }

    private Map<String, Object> buildGlobalStats(Map<String, SamplingStatCalculator> results,
                                                 int percentile, double pFraction) {
        Map<String, Object> global = new LinkedHashMap<>();
        SamplingStatCalculator total = results.get(TOTAL_LABEL);
        if (total == null || total.getCount() == 0) return global;

        final long totalCount = total.getCount();
        final long failedCount = Math.round(total.getErrorPercentage() * totalCount);

        global.put("totalRequests", totalCount);
        global.put("totalPassed", totalCount - failedCount);
        global.put("totalFailed", failedCount);
        global.put("avgResponseMs", round2(total.getMean()));
        global.put("medianResponseMs", round2(total.getPercentPoint(MEDIAN).doubleValue()));
        global.put("minResponseMs", total.getMin().longValue());
        global.put("maxResponseMs", total.getMax().longValue());
        global.put(percentile + "thPctMs", round2(total.getPercentPoint(pFraction).doubleValue()));
        global.put("stdDevMs", round2(total.getStandardDeviation()));
        global.put("errorRatePct", round2(total.getErrorPercentage() * 100.0));
        global.put("throughputTPS", round2(total.getRate()));
        global.put("receivedBandwidthKBps", round2(total.getKBPerSecond()));
        return global;
    }

    private List<Map<String, Object>> buildAnomalyTransactions(
            Map<String, SamplingStatCalculator> results, int percentile, double pFraction) {

        List<Map<String, Object>> anomalies = new ArrayList<>();
        final String pKey = percentile + "thPctMs";

        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0) continue;

            final double avg = c.getMean();
            final double pVal = c.getPercentPoint(pFraction).doubleValue();
            final double errPct = c.getErrorPercentage() * 100.0;
            final double stdDev = c.getStandardDeviation();

            boolean isAnomaly = avg > THRESHOLD_AVG_MS
                    || pVal > THRESHOLD_PCT_MS
                    || errPct > THRESHOLD_ERROR_PCT
                    || (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO);
            if (!isAnomaly) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("count", cnt);
            ep.put("failed", failed);
            ep.put("avgMs", round2(avg));
            ep.put("medianMs", round2(c.getPercentPoint(MEDIAN).doubleValue()));
            ep.put(pKey, round2(pVal));
            ep.put("stdDevMs", round2(stdDev));
            ep.put("errorRatePct", round2(errPct));
            ep.put("throughputTPS", round2(c.getRate()));
            ep.put("receivedBandwidthKBps", round2(c.getKBPerSecond()));
            ep.put("breachedThresholds", buildBreachList(avg, pVal, errPct, stdDev, percentile));
            anomalies.add(ep);
        }

        anomalies.sort((a, b) ->
                Double.compare(asDouble(b.get(pKey)), asDouble(a.get(pKey))));
        return anomalies;
    }

    // ─────────────────────────────────────────────────────────────
    // Context block
    // ─────────────────────────────────────────────────────────────

    private List<String> buildBreachList(double avg, double pVal, double errPct,
                                         double stdDev, int percentile) {
        List<String> breaches = new ArrayList<>();
        if (avg > THRESHOLD_AVG_MS) breaches.add("avgMs > " + (int) THRESHOLD_AVG_MS + "ms");
        if (pVal > THRESHOLD_PCT_MS) breaches.add(percentile + "thPct > " + (int) THRESHOLD_PCT_MS + "ms");
        if (errPct > THRESHOLD_ERROR_PCT) breaches.add("errorRate > " + THRESHOLD_ERROR_PCT + "%");
        if (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO)
            breaches.add("highVariability (stdDev/avg=" + round2(stdDev / avg) + ")");
        return breaches;
    }

    // ─────────────────────────────────────────────────────────────
    // Report structure instructions
    // ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildErrorEndpointList(
            Map<String, SamplingStatCalculator> results) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0 || c.getErrorPercentage() <= 0) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);
            final double errPct = c.getErrorPercentage() * 100.0;

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("errorCount", failed);
            ep.put("totalCount", cnt);
            ep.put("errorRatePct", round2(errPct));
            errors.add(ep);
        }
        errors.sort((a, b) ->
                Double.compare(asDouble(b.get("errorRatePct")), asDouble(a.get("errorRatePct"))));
        return errors;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private List<String> buildSlowestList(Map<String, SamplingStatCalculator> results,
                                          double pFraction, int topN) {
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0) continue;
            ranked.add(Map.entry(entry.getKey(), c.getPercentPoint(pFraction).doubleValue()));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            Map.Entry<String, Double> e = ranked.get(i);
            top.add(e.getKey() + " (" + round2(e.getValue()) + " ms)");
        }
        return top;
    }

    private String buildContextBlock(PromptRequest request) {
        List<String> parts = new ArrayList<>();
        if (notBlank(request.scenarioName())) parts.add("Scenario: " + request.scenarioName().trim());
        if (notBlank(request.users())) parts.add("Virtual Users: " + request.users().trim());
        if (notBlank(request.startTime())) parts.add("Start Time: " + request.startTime().trim());
        if (notBlank(request.duration())) parts.add("Duration: " + request.duration().trim());
        if (notBlank(request.scenarioDesc())) parts.add("Description: " + request.scenarioDesc().trim());
        return parts.isEmpty() ? "Not provided." : String.join("  |  ", parts);
    }

    private String buildReportStructureInstructions(int percentile) {
        return "## Report Structure Required\n\n"
                + "**Golden rule:** Write at the OVERALL SYSTEM level using `globalStats`. "
                + "Only mention a specific transaction by name if it appears in "
                + "`anomalyTransactions` or `errorEndpoints`. "
                + "Do not narrate or summarise every individual transaction. "
                + "Do not invent or assume values not present in the JSON.\n\n"
                + "The detailed transaction-level table is already rendered separately in the report "
                + "and does not need to be reproduced here.\n\n"
                + "### 1. Executive Summary\n"
                + "Two to three sentences: overall outcome, total requests + error rate, single most critical finding.\n\n"
                + "### 2. Bottleneck Analysis\n"
                + "Identify the primary performance bottleneck(s) using the data below. "
                + "Present a brief metric table, then diagnose *where* the constraint lies:\n"
                + "- **Response-time shape:** compare avg vs median vs " + percentile + "th-pct.\n"
                + "- **Variability signal:** stdDev > 50% of avg indicates unstable processing.\n"
                + "- **Throughput wall:** if TPS is low relative to virtual-user count, suspect saturation.\n"
                + "- **Web diagnostic signals:** high max RT with low avg → TCP retransmit or DNS delay.\n"
                + "Flag any global metric breaching: Avg RT > " + (int) THRESHOLD_AVG_MS + " ms | "
                + "Median RT > 1,500 ms | " + percentile + "th Pct > " + (int) THRESHOLD_PCT_MS
                + " ms | Error Rate > " + THRESHOLD_ERROR_PCT + "% | Std Dev/Avg > 0.5\n\n"
                + "### 3. Anomaly Highlights\n"
                + "Use `anomalyTransactions`. If empty, state all transactions performed within thresholds. "
                + "Otherwise, for each anomaly: name it, state breached thresholds, give a brief root-cause.\n\n"
                + "### 4. Error Analysis\n"
                + "Use `errorEndpoints`. If empty, state the test ran error-free. "
                + "Otherwise classify as timeout / 4xx / 5xx / connection failure.\n\n"
                + "### 5. Throughput & Capacity Assessment\n"
                + "Use `globalStats.throughputTPS` and `globalStats.receivedBandwidthKBps`. "
                + "Assess adequacy and capacity headroom.\n\n"
                + "### 6. Recommendations\n"
                + "5 actionable, prioritised recommendations. Each must reference a measured metric value "
                + "and suggest a concrete fix.\n\n"
                + "### 7. Verdict\n"
                + "State **PASS**, **CONDITIONAL PASS**, or **FAIL** with exactly 3 supporting metric values.\n\n"
                + "Format in Markdown. Use tables for metrics. Be concise.\n";
    }
}
