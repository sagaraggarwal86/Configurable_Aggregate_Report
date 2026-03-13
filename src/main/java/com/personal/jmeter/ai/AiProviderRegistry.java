package com.personal.jmeter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code ai-reporter.properties}, discovers any provider whose
 * {@code api.key} is non-blank, resolves per-provider defaults, and exposes
 * the results as an ordered list of {@link AiProviderConfig} instances.
 *
 * <h3>Provider discovery</h3>
 * <p>The registry scans for all keys matching
 * {@code ai.reporter.<name>.api.key}.  Any provider whose key value is
 * non-blank is included in the returned list.  Known providers appear first
 * (in the canonical order defined by {@link #KNOWN_PROVIDERS}); unknown
 * providers follow in alphabetical order.</p>
 *
 * <h3>Properties resolution order</h3>
 * <ol>
 *   <li>{@code $JMETER_HOME/bin/ai-reporter.properties} (user-editable file)</li>
 *   <li>JAR resource {@code /ai-reporter.properties} (bundled template fallback)</li>
 * </ol>
 *
 * <h3>Ping cache</h3>
 * <p>A successful live-ping result for a given provider key is cached for the
 * lifetime of the JVM.  A failed ping clears the cache entry so it is
 * re-tested on the next attempt.</p>
 */
public final class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    // ── Property key patterns ──────────────────────────────────────────────
    private static final String PREFIX = "ai.reporter.";
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("^ai\\.reporter\\.([^.]+)\\.api\\.key$");

    // ── Global defaults ────────────────────────────────────────────────────
    private static final int    DEFAULT_TIMEOUT   = 60;
    private static final int    DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TEMPERATURE = 0.3;

    // ── Known-provider metadata ────────────────────────────────────────────
    /**
     * Ordered list of known provider keys.  Free providers first, paid last.
     * Groq is always first when configured.
     */
    static final List<String> KNOWN_PROVIDERS = List.of(
            "groq", "gemini", "mistral", "deepseek", "openai", "claude"
    );

    private static final Map<String, String> KNOWN_LABELS = Map.of(
            "groq",     "Groq (Free)",
            "gemini",   "Gemini (Free)",
            "mistral",  "Mistral (Free)",
            "deepseek", "DeepSeek (Free)",
            "openai",   "OpenAI (Paid)",
            "claude",   "Claude (Paid)"
    );

    private static final Map<String, String> KNOWN_DEFAULT_MODELS = Map.of(
            "groq",     "llama-3.3-70b-versatile",
            "gemini",   "gemini-1.5-pro",
            "mistral",  "mistral-large-latest",
            "deepseek", "deepseek-chat",
            "openai",   "gpt-4o",
            "claude",   "claude-sonnet-4-6"
    );

    private static final Map<String, String> KNOWN_BASE_URLS = Map.of(
            "groq",     "https://api.groq.com/openai/v1",
            "gemini",   "https://generativelanguage.googleapis.com/v1beta/openai",
            "mistral",  "https://api.mistral.ai/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "openai",   "https://api.openai.com/v1",
            "claude",   "https://api.anthropic.com/v1"
    );

    // ── Known API key format prefixes (for structural validation) ──────────
    private static final Map<String, String> KNOWN_KEY_PREFIXES = Map.of(
            "groq",   "gsk_",
            "openai", "sk-",
            "claude", "sk-ant-",
            "gemini", "AIza"
    );

    // ── Ping cache: "providerKey:apiKey" → true (only successful pings are cached).
    //    Composite key ensures a rotated API key always produces a cache miss,
    //    preventing stale validation bypass after key changes. ──────────────────
    private static final ConcurrentHashMap<String, Boolean> PING_CACHE =
            new ConcurrentHashMap<>();

    // ── Singleton prevention ───────────────────────────────────────────────
    private AiProviderRegistry() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads {@code ai-reporter.properties} and returns an ordered list of all
     * providers whose {@code api.key} is non-blank.
     *
     * <p>The list is re-built on every call so that changes written to the
     * properties file between invocations are picked up immediately (the
     * dropdown triggers this on every open).</p>
     *
     * @param jmeterHome the JMeter home directory; may be {@code null}, in which
     *                   case only the JAR resource is consulted
     * @return ordered, possibly empty, list of configured providers
     */
    public static List<AiProviderConfig> loadConfiguredProviders(File jmeterHome) {
        Properties props = loadProperties(jmeterHome);
        return buildProviderList(props);
    }

    /**
     * Loads configured providers from an explicit properties file path.
     * Used by the CLI module when {@code --config} is specified.
     *
     * @param configFile path to the {@code ai-reporter.properties} file; must not be null
     * @return ordered, possibly empty, list of configured providers
     * @throws IOException if the file cannot be read
     */
    public static List<AiProviderConfig> loadConfiguredProviders(java.nio.file.Path configFile)
            throws IOException {
        Properties props = new Properties();
        try (InputStream in = java.nio.file.Files.newInputStream(configFile)) {
            props.load(in);
        }
        return buildProviderList(props);
    }

    /**
     * Validates the given provider configuration, then performs a live
     * 1-token ping to confirm the API key is accepted.
     *
     * <p>Validation steps:</p>
     * <ol>
     *   <li>Structural key-format check (prefix pattern for known providers).</li>
     *   <li>Live ping — skipped if a previous successful ping is cached.</li>
     * </ol>
     *
     * @param config  provider to validate
     * @return {@code null} on success; a user-readable error message on failure
     */
    public static String validateAndPing(AiProviderConfig config) {
        // 1 — structural check
        String formatError = checkKeyFormat(config);
        if (formatError != null) return formatError;

        // 2 — ping (skip if cached)
        if (Boolean.TRUE.equals(PING_CACHE.get(cacheKey(config)))) {
            log.debug("validateAndPing: ping cache hit for provider={}", config.providerKey);
            return null;
        }

        return executePing(config);
    }

    /**
     * Clears the ping cache entry for the given provider configuration.
     * Useful after the user edits the properties file.
     *
     * <p>The cache is keyed on {@code providerKey + ":" + apiKey} so this method
     * requires the full config to locate the correct entry.</p>
     *
     * @param config the provider config whose cache entry should be evicted
     */
    public static void evictPingCache(AiProviderConfig config) {
        PING_CACHE.remove(cacheKey(config));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Properties loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads the properties file from disk ({@code $JMETER_HOME/bin/ai-reporter.properties})
     * if it exists; otherwise falls back to the JAR resource.
     */
    public static Properties loadProperties(File jmeterHome) {
        if (jmeterHome != null) {
            File propsFile = new File(jmeterHome, "bin/ai-reporter.properties");
            if (propsFile.isFile()) {
                try (InputStream in = new FileInputStream(propsFile)) {
                    Properties p = new Properties();
                    p.load(in);
                    log.debug("loadProperties: loaded from disk: {}", propsFile.getAbsolutePath());
                    return p;
                } catch (IOException e) {
                    log.warn("loadProperties: failed to read {}. Falling back to JAR resource. reason={}",
                            propsFile.getAbsolutePath(), e.getMessage());
                }
            }
        }
        return loadFromResource();
    }

    private static Properties loadFromResource() {
        try (InputStream in = AiProviderRegistry.class.getResourceAsStream("/ai-reporter.properties")) {
            if (in == null) {
                log.warn("loadFromResource: /ai-reporter.properties not found in JAR. Returning empty properties.");
                return new Properties();
            }
            Properties p = new Properties();
            p.load(in);
            log.debug("loadFromResource: loaded from JAR resource.");
            return p;
        } catch (IOException e) {
            log.error("loadFromResource: failed to load JAR resource. reason={}", e.getMessage(), e);
            return new Properties();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Provider list construction
    // ─────────────────────────────────────────────────────────────────────────

    private static List<AiProviderConfig> buildProviderList(Properties props) {
        // Collect all provider keys that have a non-blank api.key
        Set<String> allConfigured = new LinkedHashSet<>();
        for (String name : props.stringPropertyNames()) {
            Matcher m = API_KEY_PATTERN.matcher(name);
            if (m.matches()) {
                String key = m.group(1);
                String apiKey = props.getProperty(name, "").trim();
                if (!apiKey.isEmpty()) {
                    allConfigured.add(key);
                }
            }
        }

        // Order: known providers first (in canonical order), then unknowns alphabetically
        List<String> ordered = new ArrayList<>();
        for (String known : KNOWN_PROVIDERS) {
            if (allConfigured.contains(known)) ordered.add(known);
        }
        allConfigured.stream()
                .filter(k -> !KNOWN_PROVIDERS.contains(k))
                .sorted()
                .forEach(ordered::add);

        List<AiProviderConfig> result = new ArrayList<>();
        for (String key : ordered) {
            AiProviderConfig cfg = buildConfig(key, props);
            if (cfg != null) result.add(cfg);
        }
        log.debug("buildProviderList: {} configured provider(s): {}", result.size(), ordered);
        return result;
    }

    /**
     * Builds a single {@link AiProviderConfig} from properties + built-in defaults.
     * Returns {@code null} if the resolved model or base.url is blank for an unknown provider.
     */
    private static AiProviderConfig buildConfig(String key, Properties props) {
        String apiKey   = resolve(props, key, "api.key", "");
        String model    = resolve(props, key, "model",   KNOWN_DEFAULT_MODELS.getOrDefault(key, ""));
        String baseUrl  = resolve(props, key, "base.url",KNOWN_BASE_URLS.getOrDefault(key, ""));
        int    timeout  = resolveInt(props, key, "timeout.seconds", DEFAULT_TIMEOUT);
        int    maxTok   = resolveInt(props, key, "max.tokens",      DEFAULT_MAX_TOKENS);
        double temp     = resolveDouble(props, key, "temperature",  DEFAULT_TEMPERATURE);

        if (model.isEmpty() || baseUrl.isEmpty()) {
            log.warn("buildConfig: provider '{}' skipped — model or base.url not set and no built-in default.", key);
            return null;
        }

        String label = KNOWN_LABELS.getOrDefault(key,
                Character.toUpperCase(key.charAt(0)) + key.substring(1));

        try {
            return new AiProviderConfig(key, label, apiKey, model, baseUrl, timeout, maxTok, temp);
        } catch (IllegalArgumentException e) {
            log.warn("buildConfig: provider '{}' skipped — {}.", key, e.getMessage());
            return null;
        }
    }

    private static String resolve(Properties props, String providerKey,
                                  String field, String defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        return v.isEmpty() ? defaultValue : v;
    }

    private static int resolveInt(Properties props, String providerKey,
                                  String field, int defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        if (v.isEmpty()) return defaultValue;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) {
            log.warn("resolveInt: invalid value '{}' for {}.{} — using default {}.", v, providerKey, field, defaultValue);
            return defaultValue;
        }
    }

    private static double resolveDouble(Properties props, String providerKey,
                                        String field, double defaultValue) {
        String v = props.getProperty(PREFIX + providerKey + "." + field, "").trim();
        if (v.isEmpty()) return defaultValue;
        try { return Double.parseDouble(v); }
        catch (NumberFormatException e) {
            log.warn("resolveDouble: invalid value '{}' for {}.{} — using default {}.", v, providerKey, field, defaultValue);
            return defaultValue;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Structural validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a user-readable error message if the key format is wrong for a
     * known provider, or {@code null} if the format is acceptable.
     */
    static String checkKeyFormat(AiProviderConfig config) {
        String expectedPrefix = KNOWN_KEY_PREFIXES.get(config.providerKey);
        if (expectedPrefix == null) return null; // unknown provider — no format rule
        if (!config.apiKey.startsWith(expectedPrefix)) {
            return "The " + config.displayName + " API key should start with \""
                    + expectedPrefix + "\".\n\n"
                    + "Please check the value of:\n"
                    + "  ai.reporter." + config.providerKey + ".api.key\n"
                    + "in ai-reporter.properties.";
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache key
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the composite ping-cache key from provider key and API key value.
     *
     * <p>Keying on both fields ensures that when a user rotates their API key
     * and {@code ai-reporter.properties} is reloaded, the old cache entry never
     * matches the new {@link AiProviderConfig} — forcing a fresh live ping rather
     * than silently reusing a stale success result.</p>
     *
     * @param config provider configuration
     * @return composite cache key in the form {@code "providerKey:apiKey"}
     */
    private static String cacheKey(AiProviderConfig config) {
        return config.providerKey + ":" + config.apiKey;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live ping
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a minimal 1-token request to the provider's chat completions endpoint.
     *
     * @return {@code null} on HTTP 200; a user-readable message on any failure
     */
    private static String executePing(AiProviderConfig config) {
        String url = config.chatCompletionsUrl();
        String body = buildPingBody(config);
        log.debug("executePing: pinging provider={} url={}", config.providerKey, url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response =
                    SharedHttpClient.get().send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            log.debug("executePing: provider={} status={}", config.providerKey, status);

            if (status >= 200 && status < 300) {
                PING_CACHE.put(cacheKey(config), Boolean.TRUE);
                return null;
            }
            PING_CACHE.remove(cacheKey(config));
            return buildPingErrorMessage(config, status, response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            PING_CACHE.remove(cacheKey(config));
            return "Connection to " + config.displayName + " was interrupted. Please try again.";
        } catch (IOException e) {
            PING_CACHE.remove(cacheKey(config));
            log.warn("executePing: network error for provider={}. reason={}", config.providerKey, e.getMessage());
            return "Could not connect to " + config.displayName + ".\n\n"
                    + "Please check your network connection and that the base URL is correct:\n"
                    + "  " + config.baseUrl;
        } catch (RuntimeException e) {
            PING_CACHE.remove(cacheKey(config));
            log.error("executePing: unexpected runtime error for provider={}. reason={}", config.providerKey, e.getMessage(), e);
            throw e;
        }
    }

    private static String buildPingBody(AiProviderConfig config) {
        return "{\"model\":\"" + config.model + "\","
                + "\"max_tokens\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String buildPingErrorMessage(AiProviderConfig config, int status, String responseBody) {
        if (status == 401) {
            return "The " + config.displayName + " API key was rejected (HTTP 401).\n\n"
                    + "The key may have expired or been revoked. Please update:\n"
                    + "  ai.reporter." + config.providerKey + ".api.key\n"
                    + "in ai-reporter.properties.";
        }
        if (status == 403) {
            return "Access denied by " + config.displayName + " (HTTP 403).\n\n"
                    + "Your account may lack permissions or have exceeded its quota.\n"
                    + "Please check your account at the provider's dashboard.";
        }
        if (status == 404) {
            return "Endpoint not found for " + config.displayName + " (HTTP 404).\n\n"
                    + "The base URL may be misconfigured. Please verify:\n"
                    + "  ai.reporter." + config.providerKey + ".base.url\n"
                    + "in ai-reporter.properties.\n"
                    + "Current URL: " + config.chatCompletionsUrl();
        }
        if (status == 429) {
            return "Rate limit exceeded for " + config.displayName + " (HTTP 429).\n\n"
                    + "Too many requests have been sent to the provider.\n"
                    + "Please wait a moment and try again.";
        }
        if (status == 500) {
            return "Internal server error from " + config.displayName + " (HTTP 500).\n\n"
                    + "The provider encountered an unexpected error.\n"
                    + "Please try again or check the provider's status page.";
        }
        if (status == 503) {
            return "Service temporarily unavailable for " + config.displayName + " (HTTP 503).\n\n"
                    + "The provider is currently unavailable.\n"
                    + "Please try again later or check the provider's status page.";
        }
        if (status == 408 || status == 504) {
            return "Request timed out for " + config.displayName + " (HTTP " + status + ").\n\n"
                    + "The provider did not respond in time.\n"
                    + "Please check your network connection and try again.";
        }
        return "Unexpected response from " + config.displayName + " (HTTP " + status + ").\n\n"
                + "Provider: " + config.providerKey + "\n\n"
                + "Response body:\n" + responseBody;
    }
}