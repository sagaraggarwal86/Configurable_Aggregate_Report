package com.personal.jmeter.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.personal.jmeter.parser.JTLParser;
import java.util.*;

/**
 * Builds the two-part AI analysis prompt from already-aggregated JMeter results.
 *
 * <p><b>Standard 21 PromptBuilder contract:</b> {@code build} returns a
 * {@code PromptContent} containing a static {@code role:"system"} message
 * (the analytical framework with Layers 1–5 and seven mandatory report sections)
 * and a dynamic {@code role:"user"} message containing runtime test data with
 * all Standard 21 placeholders substituted.</p>
 *
 * <h3>JSON sections sent in the user message</h3>
 * <ul>
 *   <li>{@code globalStats}         — TOTAL-row KPIs</li>
 *   <li>{@code anomalyTransactions} — transactions breaching at least one threshold</li>
 *   <li>{@code errorEndpoints}      — transactions with any errors</li>
 *   <li>{@code slowestEndpoints}    — threshold-based slowest transactions (label + Pnn value)</li>
 *   <li>{@code allTransactionStats} — compact per-label RT/error lookup for SLA evaluation</li>
 *   <li>{@code tpsSeries}           — compact TPS time-series for plateau assessment</li>
 * </ul>
 *
 * <h3>Anomaly error threshold resolution</h3>
 * <p>The error-rate threshold used to classify a transaction as an anomaly is
 * resolved at build time from the user-configured SLA when present, falling back
 * to {@link #THRESHOLD_ERROR_PCT} when no SLA is configured. This ensures
 * {@code breachedThresholds} values are always consistent with the SLA threshold
 * the user actually cares about — eliminating contradictory signals between
 * {@code anomalyTransactions} and the SLA evaluation in the system prompt.</p>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final Gson GSON = new GsonBuilder().create(); // compact JSON — reduces AI token payload
    private static final String TOTAL_LABEL = "TOTAL";
    private static final double MEDIAN = 0.50;
    /**
     * Minimum number of entries always included in {@code slowestEndpoints},
     * regardless of whether they breach the threshold ratio.
     * Guarantees the AI always has at least one concrete slow transaction to cite.
     */
    private static final int SLOWEST_MIN = 3;
    /**
     * Hard cap on {@code slowestEndpoints} entries — bounds token cost on pathological
     * distributions where many transactions cluster just above the threshold.
     */
    private static final int SLOWEST_MAX = 15;
    /**
     * Threshold ratio applied to the median P90 across all non-TOTAL labels.
     * A transaction qualifies as "notably slow" when its P90 exceeds
     * {@code medianP90 × SLOWEST_THRESHOLD_RATIO}.
     *
     * <p>Example: if median P90 is 280ms, the threshold is 420ms.
     * Transactions above 420ms qualify; those below do not.
     * A ratio of 1.5 represents 50% above median — a meaningful outlier
     * without being so tight that half the labels qualify, or so loose
     * that only extreme outliers are included.</p>
     */
    private static final double SLOWEST_THRESHOLD_RATIO = 1.5;
    /**
     * Maximum number of entries emitted in the {@code errorEndpoints} JSON section.
     * Applied after sorting by {@code errorRatePct} descending so the highest-rate
     * transactions — those most likely to breach the SLA — are always included.
     * Prevents token waste on large JTLs where errors are thinly spread across
     * many labels with negligible per-transaction rates.
     */
    private static final int MAX_ERROR_ENDPOINTS = 15;
    private static final String KEY_ERROR_RATE_PCT = "errorRatePct";

    // ── Anomaly thresholds ───────────────────────────────────────
    private static final double THRESHOLD_AVG_MS        = 2_000.0;
    private static final double THRESHOLD_PCT_MS        = 3_000.0;
    /**
     * Fallback error-rate anomaly threshold (%) used when no user SLA is configured.
     * When a user SLA is configured via the UI or CLI, that value replaces this
     * threshold so that anomaly detection and SLA evaluation share a single
     * consistent cut-off. See {@link #parseErrorSlaThreshold(String)}.
     */
    static final double THRESHOLD_ERROR_PCT      = 1.0;
    private static final double THRESHOLD_STD_DEV_RATIO = 0.5;

    /** Sentinel returned by {@link #parseErrorSlaThreshold} when no SLA is configured. */
    private static final double NO_ERROR_SLA = -1.0;

    // ─────────────────────────────────────────────────────────────
    // Latency context record
    // ─────────────────────────────────────────────────────────────

    /**
     * Carries the three Latency/Connect fields derived during JTL parsing,
     * and needed by the AI prompt's Advanced Web Diagnostics section.
     *
     * <p>Bundled as a record to keep {@link #build}'s parameter count bounded
     * and to give the three values a named, cohesive type.</p>
     *
     * @param avgLatencyMs   average Latency (TTFB) in ms across all samples;
     *                       0 when {@code latencyPresent} is false
     * @param avgConnectMs   average Connect time in ms across all samples;
     *                       0 when {@code latencyPresent} is false
     * @param latencyPresent true iff at least one sample had a non-zero Latency value;
     *                       false means the JTL has no usable Latency data and the
     *                       prompt must use inferred mode for timing decomposition
     */
    public record LatencyContext(long avgLatencyMs, long avgConnectMs, boolean latencyPresent) {
        /**
         * Sentinel for absent latency data — used when no JTL has been parsed
         * or all Latency values were zero.  Causes the prompt to enter inferred mode.
         */
        public static final LatencyContext ABSENT = new LatencyContext(0L, 0L, false);
    }

    /** Resolved system prompt — loaded from file or JAR resource. */
    private final String systemPrompt;

    /**
     * Production constructor: receives the system prompt from {@link PromptLoader}.
     *
     * @param systemPrompt non-blank system prompt text
     */
    public PromptBuilder(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank())
            throw new IllegalArgumentException("systemPrompt must not be blank");
        this.systemPrompt = systemPrompt;
    }

    /**
     * No-arg constructor for tests and backward compatibility.
     * Uses a minimal hardcoded stub prompt so tests have no classpath dependency.
     * Production code always uses {@link #PromptBuilder(String)} with a prompt
     * loaded by {@link com.personal.jmeter.ai.PromptLoader}.
     */
    PromptBuilder() {
        this("You are a performance engineer. Analyse the JMeter results and report findings.");
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
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
     * Convenience overload — delegates to
     * {@link #build(Map, int, PromptRequest, List, LatencyContext, List)}
     * with an empty {@code timeBuckets} list, disabling TPS plateau assessment.
     * Used by tests and callers that do not have time-bucket data available.
     *
     * @param results          per-label aggregated statistics; must not be null
     * @param percentile       percentile to report (1–99)
     * @param request          scenario context; must not be null
     * @param errorTypeSummary top-5 failure types; empty list if no errors
     * @param latency          Latency/Connect context; use {@link LatencyContext#ABSENT} if absent
     * @return {@code PromptContent} with system and user messages
     */
    public PromptContent build(Map<String, SamplingStatCalculator> results,
                               int percentile,
                               PromptRequest request,
                               List<Map<String, Object>> errorTypeSummary,
                               LatencyContext latency) {
        return build(results, percentile, request, errorTypeSummary, latency,
                Collections.emptyList());
    }

    /**
     * Builds the two-part AI analysis prompt from aggregated JMeter results.
     *
     * @param results           per-label aggregated statistics; must not be null
     * @param percentile        percentile to report (1–99)
     * @param request           scenario context (users, name, description, timing); must not be null
     * @param errorTypeSummary  top-5 failure types from the full JTL; empty list if no errors
     * @param latency           average Latency/Connect values and presence flag; use
     *                          {@link LatencyContext#ABSENT} when no latency data is available
     * @param timeBuckets       ordered time buckets from the JTL parser; null or empty list
     *                          disables TPS plateau assessment in the prompt
     * @return {@code PromptContent} with system prompt and user message suitable for the AI API
     */
    public PromptContent build(Map<String, SamplingStatCalculator> results,
                               int percentile,
                               PromptRequest request,
                               List<Map<String, Object>> errorTypeSummary,
                               LatencyContext latency,
                               List<JTLParser.TimeBucket> timeBuckets) {
        Objects.requireNonNull(results,  "results must not be null");
        Objects.requireNonNull(request,  "request must not be null");
        LatencyContext safeLatency = (latency != null) ? latency : LatencyContext.ABSENT;
        List<Map<String, Object>> safeErrorTypes =
                (errorTypeSummary != null) ? errorTypeSummary : Collections.emptyList();
        log.info("build: building prompt. labels={}, percentile={}, errorTypes={}, latencyPresent={}",
                results.size(), percentile, safeErrorTypes.size(), safeLatency.latencyPresent());

        List<JTLParser.TimeBucket> safeBuckets =
                (timeBuckets != null) ? timeBuckets : Collections.emptyList();
        String userMessage = buildUserMessage(results, percentile, request, safeErrorTypes, safeLatency, safeBuckets);
        return new PromptContent(systemPrompt, userMessage);
    }

    // ─────────────────────────────────────────────────────────────
    // User message assembly (Standard 21 dynamic substitution)
    // ─────────────────────────────────────────────────────────────

    private String buildUserMessage(Map<String, SamplingStatCalculator> results,
                                    int percentile, PromptRequest request,
                                    List<Map<String, Object>> errorTypeSummary,
                                    LatencyContext latency,
                                    List<JTLParser.TimeBucket> timeBuckets) {
        // Parse user SLA once here — passed to buildSummary() so anomaly detection
        // and the SLA threshold the AI evaluates share the same numeric cut-off.
        final double userErrorSlaThreshold = parseErrorSlaThreshold(request.errorSlaThresholdPct());

        String json = GSON.toJson(
                buildSummary(results, percentile, errorTypeSummary, latency,
                        userErrorSlaThreshold, timeBuckets, request));
        return """
                DATA SOURCE: All metrics below reflect the user's currently \
                configured view — time window, transaction scope, and \
                percentile have already been applied to the dataset. \
                Do not adjust, extrapolate, or question the scope. \
                Report on what is provided.

                Scenario              : %s
                Thread Group          : %s
                Description           : %s
                Users                 : %s
                Start Time            : %s
                End Time              : %s
                Duration              : %s
                Configured Percentile : P%d

                SLA Thresholds (user-configured):
                  Error Rate SLA      : %s
                  Response Time SLA   : %s on %s

                Global Statistics (JSON):
                %s""".formatted(
                orNotProvided(request.scenarioName()),
                orNotProvided(request.threadGroupName()),
                orNotProvided(request.scenarioDesc()),
                orNotProvided(request.users()),
                orNotProvided(request.startTime()),
                orNotProvided(request.endTime()),
                orNotProvided(request.duration()),
                request.configuredPercentile(),
                request.errorSlaThresholdPct(),
                request.rtSlaThresholdMs(),
                request.rtSlaMetric(),
                json);
    }

    private static String orNotProvided(String value) {
        return (value == null || value.isBlank()) ? "Not provided" : value.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // Error SLA threshold parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses the user-configured error SLA threshold string into a numeric value.
     *
     * <p>The string arrives as {@code "5%"} (UI/CLI formatted) or
     * {@code "Not configured"} when the user left the field blank.
     * Strips the trailing {@code %} if present before parsing.</p>
     *
     * @param errorSlaThresholdPct raw SLA string from {@link PromptRequest}
     * @return parsed threshold as a positive double, or {@link #NO_ERROR_SLA} (-1)
     *         if absent, blank, "Not configured", or unparseable
     */
    static double parseErrorSlaThreshold(String errorSlaThresholdPct) {
        if (errorSlaThresholdPct == null || errorSlaThresholdPct.isBlank()) return NO_ERROR_SLA;
        String trimmed = errorSlaThresholdPct.trim();
        if (trimmed.equalsIgnoreCase("Not configured")) return NO_ERROR_SLA;
        try {
            double val = Double.parseDouble(trimmed.replace("%", "").trim());
            return val > 0 ? val : NO_ERROR_SLA;
        } catch (NumberFormatException e) {
            log.warn("parseErrorSlaThreshold: unparseable value '{}' — treating as not configured.",
                    errorSlaThresholdPct);
            return NO_ERROR_SLA;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RT SLA threshold parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses the user-configured RT SLA threshold string into a numeric value.
     * Strips the trailing " ms" or "ms" suffix before parsing.
     *
     * @param rtSlaThresholdMs raw RT SLA string from {@link PromptRequest}
     *                         (e.g. "100 ms", "2000 ms", "Not configured")
     * @return parsed threshold as a positive double, or -1 if absent/unparseable
     */
    static double parseRtSlaThreshold(String rtSlaThresholdMs) {
        if (rtSlaThresholdMs == null || rtSlaThresholdMs.isBlank()) return NO_ERROR_SLA;
        String trimmed = rtSlaThresholdMs.trim();
        if (trimmed.equalsIgnoreCase("Not configured")) return NO_ERROR_SLA;
        try {
            double val = Double.parseDouble(
                    trimmed.replaceAll("(?i)\\s*ms$", "").trim());
            return val > 0 ? val : NO_ERROR_SLA;
        } catch (NumberFormatException e) {
            log.warn("parseRtSlaThreshold: unparseable value '{}' — treating as not configured.",
                    rtSlaThresholdMs);
            return NO_ERROR_SLA;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // JSON summary — single-pass implementation
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the JSON summary map in a single pass over the results map.
     *
     * <p>The effective error-rate anomaly threshold is resolved here from the
     * user SLA when configured, falling back to {@link #THRESHOLD_ERROR_PCT}.
     * This ensures {@code anomalyTransactions} and {@code breachedThresholds}
     * always reflect the same threshold the user configured — eliminating the
     * contradiction where a transaction appears as an anomaly for "errorRate > 1%"
     * while the user's SLA is 5% and the SLA evaluation returns WITHIN.</p>
     *
     * @param userErrorSlaThreshold parsed user error SLA, or {@link #NO_ERROR_SLA} if absent
     */
    private Map<String, Object> buildSummary(Map<String, SamplingStatCalculator> results,
                                             int percentile,
                                             List<Map<String, Object>> errorTypeSummary,
                                             LatencyContext latency,
                                             double userErrorSlaThreshold,
                                             List<JTLParser.TimeBucket> timeBuckets,
                                             PromptRequest request) {
        final double pFraction = percentile / 100.0;
        final String pKey      = percentile + "thPctMs";

        // Resolve effective error threshold — user SLA when configured, fallback otherwise.
        // This is the single source of truth for what constitutes an error-rate anomaly,
        // keeping anomaly detection and SLA evaluation consistent.
        final double effectiveErrorThreshold = (userErrorSlaThreshold > 0)
                ? userErrorSlaThreshold
                : THRESHOLD_ERROR_PCT;

        List<Map<String, Object>>       anomalies = new ArrayList<>();
        List<Map<String, Object>>       errors    = new ArrayList<>();
        List<Map<String, Object>>       allStats  = new ArrayList<>();
        List<Map.Entry<String, Double>> ranked    = new ArrayList<>();

        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            if (TOTAL_LABEL.equals(entry.getKey())) continue;
            SamplingStatCalculator c = entry.getValue();
            if (c.getCount() == 0) continue;

            // Compute once — shared by anomaly, error-endpoint, and slowest checks.
            // getPercentPoint() is O(n log n) over stored sample values — calling it
            // once per label avoids the repeated recomputation of the previous 3-pass design.
            final double avg    = c.getMean();
            final double pVal   = c.getPercentPoint(pFraction).doubleValue();
            final double errPct = c.getErrorPercentage() * 100.0;
            final double stdDev = c.getStandardDeviation();

            // ── Slowest endpoints accumulator ─────────────────────────────────
            ranked.add(Map.entry(entry.getKey(), pVal));

            // ── All-transaction compact stats accumulator ────────────────────
            // Compact 4-field entry per label — enables per-transaction RT SLA
            // evaluation in the AI prompt. All values already computed above;
            // no additional SamplingStatCalculator calls.
            Map<String, Object> txStat = new LinkedHashMap<>();
            txStat.put("label",            entry.getKey());
            txStat.put("avgMs",            round2(avg));
            txStat.put("pnnMs",            round2(pVal));
            txStat.put(KEY_ERROR_RATE_PCT, round2(errPct));
            allStats.add(txStat);

            // ── Error endpoints accumulator ───────────────────────────────────
            if (c.getErrorPercentage() > 0) {
                final long cnt    = c.getCount();
                final long failed = Math.round(c.getErrorPercentage() * cnt);
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("label",            entry.getKey());
                ep.put("errorCount",       failed);
                ep.put("totalCount",       cnt);
                ep.put(KEY_ERROR_RATE_PCT, round2(errPct));
                errors.add(ep);
            }

            // ── Anomaly transactions accumulator ──────────────────────────────
            // Error-rate condition uses effectiveErrorThreshold — not the hardcoded
            // constant — so the breach reason in breachedThresholds matches the
            // SLA threshold the user actually configured.
            boolean isAnomaly = avg > THRESHOLD_AVG_MS
                    || pVal > THRESHOLD_PCT_MS
                    || errPct > effectiveErrorThreshold
                    || (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO);
            if (isAnomaly) {
                final long cnt    = c.getCount();
                final long failed = Math.round(c.getErrorPercentage() * cnt);
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("label",                 entry.getKey());
                ep.put("count",                 cnt);
                ep.put("failed",                failed);
                ep.put("avgMs",                 round2(avg));
                ep.put("medianMs",              round2(c.getPercentPoint(MEDIAN).doubleValue()));
                ep.put(pKey,                    round2(pVal));
                ep.put("stdDevMs",              round2(stdDev));
                ep.put(KEY_ERROR_RATE_PCT,      round2(errPct));
                ep.put("throughputTPS",         round2(c.getRate()));
                ep.put("receivedBandwidthKBps", round2(c.getKBPerSecond()));
                // breachedThresholds: explicit list of which thresholds fired for this
                // transaction. Uses effectiveErrorThreshold so the AI sees "errorRate > 5%"
                // when the user configured 5% — not the internal fallback value.
                ep.put("breachedThresholds",    buildBreachList(
                        avg, pVal, errPct, stdDev, percentile, effectiveErrorThreshold));
                anomalies.add(ep);
            }
        }

        // ── Post-loop sort / trim ─────────────────────────────────────────────
        anomalies.sort((a, b) ->
                Double.compare(asDouble(b.get(pKey)), asDouble(a.get(pKey))));
        errors.sort((a, b) ->
                Double.compare(asDouble(b.get(KEY_ERROR_RATE_PCT)), asDouble(a.get(KEY_ERROR_RATE_PCT))));
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // ── Threshold-based slowest selection ────────────────────────────────
        // Instead of a fixed top-N, include transactions whose P90 exceeds
        // medianP90 × SLOWEST_THRESHOLD_RATIO. This adapts to any JTL profile:
        // fast systems (50ms avg) and slow systems (2000ms avg) both produce a
        // meaningful outlier set rather than an arbitrary fixed count.
        // Floor of SLOWEST_MIN guarantees at least 3 entries on any JTL.
        // Cap of SLOWEST_MAX prevents token bloat on pathological distributions.
        final double medianP90 = ranked.isEmpty() ? 0.0
                : ranked.get(ranked.size() / 2).getValue();
        final double slowThreshold = medianP90 * SLOWEST_THRESHOLD_RATIO;

        List<String> slowest = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            if (slowest.size() >= SLOWEST_MAX) break;
            // Always include up to SLOWEST_MIN; after that only if above threshold
            if (slowest.size() < SLOWEST_MIN || e.getValue() > slowThreshold) {
                slowest.add(e.getKey() + " (" + round2(e.getValue()) + " ms)");
            }
        }

        // Cap errorEndpoints to MAX_ERROR_ENDPOINTS after sorting — keeps only the
        // highest-error-rate transactions. The sort guarantees SLA-relevant entries
        // are always retained. subList() returns a view — no copy allocation.
        List<Map<String, Object>> cappedErrors = errors.size() > MAX_ERROR_ENDPOINTS
                ? errors.subList(0, MAX_ERROR_ENDPOINTS)
                : errors;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("globalStats",          buildGlobalStats(results, percentile, pFraction, latency));
        summary.put("anomalyTransactions",  anomalies);
        summary.put("errorEndpoints",       cappedErrors);
        summary.put("errorTypeSummary",     errorTypeSummary);
        summary.put("slowestEndpoints",     slowest);
        // allTransactionStats: compact per-label lookup table (label, avgMs, pnnMs,
        // errorRatePct) enabling per-transaction RT SLA evaluation in the AI prompt.
        // Sorted by pnnMs descending so the AI can scan from worst to best.
        allStats.sort((a, b) -> Double.compare(asDouble(b.get("pnnMs")), asDouble(a.get("pnnMs"))));
        summary.put("allTransactionStats",  allStats);
        // tpsSeries: compact TPS time-series enabling genuine plateau assessment.
        // Only epochMs and tps are included — other bucket fields are redundant
        // with globalStats and allTransactionStats. Null/empty = absent, and the
        // prompt falls back to the DEFAULT rule (no plateau assertion).
        summary.put("tpsSeries", buildTpsSeries(timeBuckets));
        // rtSlaSummary: pre-computed RT SLA verdict — Java does the arithmetic
        // so the AI never needs to compare observedMs against thresholdMs itself.
        // This eliminates model-level numeric comparison errors entirely.
        summary.put("rtSlaSummary",    buildRtSlaSummary(allStats, request));
        // errorSlaSummary: pre-computed error-rate SLA verdict — mirrors rtSlaSummary.
        // Eliminates the same class of model arithmetic errors for error-rate SLA
        // (digit misread, comparison inversion, wrong overage calculation).
        // Uses allStats as source — covers all 100% of transactions, not just the
        // capped errorEndpoints list — so no transaction is silently excluded.
        summary.put("errorSlaSummary", buildErrorSlaSummary(allStats, userErrorSlaThreshold));
        return summary;
    }

    // ─────────────────────────────────────────────────────────────
    // Global stats — reads from the TOTAL row
    // ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildGlobalStats(Map<String, SamplingStatCalculator> results,
                                                 int percentile, double pFraction,
                                                 LatencyContext latency) {
        Map<String, Object> global = new LinkedHashMap<>();
        SamplingStatCalculator total = results.get(TOTAL_LABEL);
        if (total == null || total.getCount() == 0) return global;

        final long totalCount  = total.getCount();
        final long failedCount = Math.round(total.getErrorPercentage() * totalCount);

        global.put("totalRequests",         totalCount);
        global.put("totalPassed",           totalCount - failedCount);
        global.put("totalFailed",           failedCount);
        global.put("configuredPercentile",  percentile);
        global.put("avgResponseMs",         round2(total.getMean()));
        global.put("medianResponseMs",      round2(total.getPercentPoint(MEDIAN).doubleValue()));
        global.put("minResponseMs",         total.getMin().longValue());
        global.put("maxResponseMs",         total.getMax().longValue());
        global.put(percentile + "thPctMs",  round2(total.getPercentPoint(pFraction).doubleValue()));
        global.put("p99ResponseMs",         round2(total.getPercentPoint(0.99).doubleValue()));
        global.put("stdDevMs",              round2(total.getStandardDeviation()));
        global.put(KEY_ERROR_RATE_PCT,      round2(total.getErrorPercentage() * 100.0));
        global.put("throughputTPS",         round2(total.getRate()));
        global.put("receivedBandwidthKBps", round2(total.getKBPerSecond()));
        // Latency decomposition fields — consumed by the Advanced Web Diagnostics
        // section of the AI prompt to decide between direct and inferred mode.
        global.put("avgLatencyMs",   latency.avgLatencyMs());
        global.put("avgConnectMs",   latency.avgConnectMs());
        global.put("latencyPresent", latency.latencyPresent());
        return global;
    }

    // ─────────────────────────────────────────────────────────────
    // Breach list — per-transaction, called only for confirmed anomalies
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the list of human-readable breach reasons for one anomaly transaction.
     *
     * <p>Each string names the breached threshold and its exact cut-off value,
     * e.g. {@code "errorRate > 5.0%"} or {@code "avgMs > 2000ms"}. The error-rate
     * string uses {@code errorThreshold} — not the hardcoded fallback — so the AI
     * sees the same value the user configured as their SLA.</p>
     *
     * @param avg            transaction average response time (ms)
     * @param pVal           transaction Nth-percentile response time (ms)
     * @param errPct         transaction error rate (0–100)
     * @param stdDev         transaction standard deviation (ms)
     * @param percentile     configured percentile (1–99)
     * @param errorThreshold effective error-rate threshold — user SLA or fallback
     * @return non-empty list of breach reason strings; empty list if no condition fired
     */
    // ─────────────────────────────────────────────────────────────
    // RT SLA summary — pre-computed verdict, eliminates model arithmetic
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a pre-computed RT SLA evaluation summary.
     *
     * <p>Java performs the threshold comparison — not the AI model.
     * This eliminates the class of model errors where the model
     * misreads the threshold value or inverts the comparison direction.</p>
     *
     * <p>The returned map contains:
     * <ul>
     *   <li>{@code configured}           — true if RT SLA is active</li>
     *   <li>{@code thresholdMs}           — numeric threshold (absent if not configured)</li>
     *   <li>{@code metric}                — "avgMs" or "pnnMs"</li>
     *   <li>{@code verdict}               — "BREACH" or "WITHIN" or "NOT_CONFIGURED"</li>
     *   <li>{@code worstLabel}            — label with highest observed value</li>
     *   <li>{@code worstObservedMs}       — its observed metric value</li>
     *   <li>{@code breachingTransactions} — list of {label, observedMs} for all breaches</li>
     * </ul></p>
     *
     * @param allStats per-label compact stats (label, avgMs, pnnMs, errorRatePct)
     * @param request  scenario/SLA context
     * @return pre-computed RT SLA summary map
     */
    private static Map<String, Object> buildRtSlaSummary(
            List<Map<String, Object>> allStats, PromptRequest request) {

        Map<String, Object> result = new LinkedHashMap<>();

        double thresholdMs = parseRtSlaThreshold(request.rtSlaThresholdMs());
        if (thresholdMs <= 0) {
            result.put("configured", false);
            result.put("verdict",    "NOT_CONFIGURED");
            return result;
        }

        // Determine which field to compare — avgMs for "Avg (ms)", pnnMs for percentile
        String metric = (request.rtSlaMetric() != null
                && request.rtSlaMetric().toLowerCase().contains("avg"))
                ? "avgMs" : "pnnMs";

        result.put("configured",   true);
        result.put("thresholdMs",  (long) thresholdMs);
        result.put("metric",       metric);

        // Find worst transaction and all breaches — pure Java arithmetic
        List<Map<String, Object>> breaches = new ArrayList<>();
        String worstLabel = null;
        double worstValue = -1.0;

        for (Map<String, Object> tx : allStats) {
            double observed = asDouble(tx.get(metric));
            String label    = String.valueOf(tx.get("label"));
            if (observed > worstValue) {
                worstValue = observed;
                worstLabel = label;
            }
            if (observed > thresholdMs) {
                Map<String, Object> breach = new LinkedHashMap<>();
                breach.put("label",      label);
                breach.put("observedMs", round2(observed));
                breach.put("overageMs",  round2(observed - thresholdMs));
                breaches.add(breach);
            }
        }

        // Sort breaches by observed value descending
        breaches.sort((a, b) ->
                Double.compare(asDouble(b.get("observedMs")), asDouble(a.get("observedMs"))));

        result.put("verdict",               breaches.isEmpty() ? "WITHIN" : "BREACH");
        result.put("worstLabel",             worstLabel != null ? worstLabel : "");
        result.put("worstObservedMs",        round2(worstValue));
        result.put("worstOverageMs",         round2(worstValue - thresholdMs));
        result.put("breachingTransactions",  breaches);
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Error Rate SLA summary — pre-computed verdict, eliminates model arithmetic
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a pre-computed error-rate SLA evaluation summary.
     *
     * <p>Mirrors {@link #buildRtSlaSummary} — Java performs all comparisons so
     * the AI model never needs to iterate transactions and compare error rates
     * against the threshold itself. Eliminates digit-misread, comparison-inversion,
     * and wrong-overage errors observed in weaker models.</p>
     *
     * <p>The returned map contains:
     * <ul>
     *   <li>{@code configured}             — true if error rate SLA is active</li>
     *   <li>{@code thresholdPct}           — numeric threshold % (absent if not configured)</li>
     *   <li>{@code verdict}                — "BREACH" or "WITHIN" or "NOT_CONFIGURED"</li>
     *   <li>{@code worstLabel}             — transaction with highest error rate</li>
     *   <li>{@code worstErrorRatePct}      — its observed error rate</li>
     *   <li>{@code worstOveragePct}        — worstErrorRatePct − thresholdPct</li>
     *   <li>{@code breachingTransactions}  — list of {label, errorRatePct, overagePct}</li>
     * </ul></p>
     *
     * <p>Uses {@code allStats} as the source so every transaction is evaluated —
     * not just the capped {@code errorEndpoints} list which covers at most
     * {@value #MAX_ERROR_ENDPOINTS} entries.</p>
     *
     * @param allStats              per-label compact stats (label, avgMs, pnnMs, errorRatePct)
     * @param userErrorSlaThreshold parsed error SLA threshold (%); {@link #NO_ERROR_SLA} if absent
     * @return pre-computed error-rate SLA summary map
     */
    private static Map<String, Object> buildErrorSlaSummary(
            List<Map<String, Object>> allStats, double userErrorSlaThreshold) {

        Map<String, Object> result = new LinkedHashMap<>();

        if (userErrorSlaThreshold <= 0) {
            result.put("configured", false);
            result.put("verdict",    "NOT_CONFIGURED");
            return result;
        }

        result.put("configured",   true);
        result.put("thresholdPct", round2(userErrorSlaThreshold));

        // Find worst transaction and all breaches — pure Java arithmetic
        List<Map<String, Object>> breaches  = new ArrayList<>();
        String worstLabel    = null;
        double worstErrorPct = -1.0;

        for (Map<String, Object> tx : allStats) {
            double observed = asDouble(tx.get(KEY_ERROR_RATE_PCT));
            String label    = String.valueOf(tx.get("label"));
            if (observed > worstErrorPct) {
                worstErrorPct = observed;
                worstLabel    = label;
            }
            if (observed > userErrorSlaThreshold) {
                Map<String, Object> breach = new LinkedHashMap<>();
                breach.put("label",          label);
                breach.put(KEY_ERROR_RATE_PCT, round2(observed));
                breach.put("overagePct",      round2(observed - userErrorSlaThreshold));
                breaches.add(breach);
            }
        }

        // Sort breaches by error rate descending — worst first
        breaches.sort((a, b) ->
                Double.compare(asDouble(b.get(KEY_ERROR_RATE_PCT)),
                        asDouble(a.get(KEY_ERROR_RATE_PCT))));

        result.put("verdict",              breaches.isEmpty() ? "WITHIN" : "BREACH");
        result.put("worstLabel",           worstLabel != null ? worstLabel : "");
        result.put("worstErrorRatePct",    round2(worstErrorPct));
        result.put("worstOveragePct",      round2(worstErrorPct - userErrorSlaThreshold));
        result.put("breachingTransactions", breaches);
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // TPS series builder
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a compact TPS time-series from the parsed time buckets.
     *
     * <p>Each entry contains only two fields:
     * <ul>
     *   <li>{@code t} — epoch milliseconds (bucket start time)</li>
     *   <li>{@code tps} — transactions per second for that bucket</li>
     * </ul>
     * Other bucket fields (avgResponseMs, errorPct, kbps) are omitted —
     * they are already represented in {@code globalStats} and
     * {@code allTransactionStats}. The reduced payload keeps token cost
     * at approximately 3,150 tokens for a 3-hour test at 30-second buckets.</p>
     *
     * <p>Returns an empty list when {@code timeBuckets} is null or empty,
     * signalling the prompt to apply the DEFAULT classification rule.</p>
     *
     * @param timeBuckets ordered list of time buckets from the JTL parser
     * @return compact list of {@code {t, tps}} maps; empty list if no data
     */
    private static List<Map<String, Object>> buildTpsSeries(
            List<JTLParser.TimeBucket> timeBuckets) {
        if (timeBuckets == null || timeBuckets.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> series = new ArrayList<>(timeBuckets.size());
        for (JTLParser.TimeBucket b : timeBuckets) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("t",   b.epochMs);
            entry.put("tps", round2(b.tps));
            series.add(entry);
        }
        return series;
    }

    private static List<String> buildBreachList(double avg, double pVal, double errPct,
                                                double stdDev, int percentile,
                                                double errorThreshold) {
        List<String> breaches = new ArrayList<>();
        if (avg > THRESHOLD_AVG_MS)
            breaches.add("avgMs > " + (int) THRESHOLD_AVG_MS + "ms");
        if (pVal > THRESHOLD_PCT_MS)
            breaches.add(percentile + "thPct > " + (int) THRESHOLD_PCT_MS + "ms");
        if (errPct > errorThreshold)
            breaches.add("errorRate > " + errorThreshold + "%");
        if (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO)
            breaches.add("highVariability (stdDev/avg=" + round2(stdDev / avg) + ")");
        return breaches;
    }
}