package com.personal.jmeter.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Calls the Groq AI API (Llama 3.3-70B) and returns the generated report
 * in Markdown format.
 *
 * <p>The {@link HttpClient} is a shared singleton — it manages a connection
 * pool internally and must not be recreated per request.</p>
 *
 * <p>API key resolution order:
 * <ol>
 *   <li>Exact env-var match: {@code Groq_APIKEY}</li>
 *   <li>Case-insensitive scan of all env vars (handles Windows normalised env blocks)</li>
 *   <li>JVM system property {@code -DGroq_APIKEY=...}</li>
 * </ol>
 */
public class AiReportService {

    /**
     * Name of the environment variable holding the Groq API key.
     */
    public static final String ENV_VAR_NAME = "Groq_APIKEY";
    private static final Logger log = LoggerFactory.getLogger(AiReportService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL_ID = "llama-3.3-70b-versatile";

    /**
     * Long-lived singleton — reusing one client allows connection pooling and
     * avoids the overhead of a new TLS handshake per request.
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final String apiKey;

    /**
     * Constructs the service with the given API key.
     *
     * @param apiKey Groq API key; must not be null or blank
     * @throws IllegalArgumentException if the key is blank after trimming
     */
    public AiReportService(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        final String trimmed = apiKey.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = trimmed;
    }

    // ─────────────────────────────────────────────────────────────
    // API key resolution
    // ─────────────────────────────────────────────────────────────

    /**
     * Reads the Groq API key from the environment.
     *
     * @return the trimmed key, or {@code null} if absent or blank
     */
    public static String readApiKeyFromEnv() {
        String key = System.getenv(ENV_VAR_NAME);
        if (isPresent(key)) {
            return key.trim();
        }

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (ENV_VAR_NAME.equalsIgnoreCase(entry.getKey()) && isPresent(entry.getValue())) {
                return entry.getValue().trim();
            }
        }

        String prop = System.getProperty(ENV_VAR_NAME);
        return isPresent(prop) ? prop.trim() : null;
    }

    // ─────────────────────────────────────────────────────────────
    // Report generation
    // ─────────────────────────────────────────────────────────────

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    // ─────────────────────────────────────────────────────────────
    // Private implementation
    // ─────────────────────────────────────────────────────────────

    /**
     * Sends {@code prompt} to Groq and returns the AI-generated Markdown report.
     *
     * @param prompt fully assembled analysis prompt; must not be null
     * @return AI-generated report text in Markdown
     * @throws IOException              on HTTP error, timeout, or unparseable response
     * @throws IllegalArgumentException if prompt is null
     */
    public String generateReport(String prompt) throws IOException {
        Objects.requireNonNull(prompt, "prompt must not be null");
        log.info("generateReport: sending prompt. length={}", prompt.length());
        return callGroq(prompt);
    }

    private String callGroq(String prompt) throws IOException {
        String requestBody = buildRequestBody(prompt);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("callGroq: request interrupted. reason={}", e.getMessage(), e);
            throw new IOException("Groq API request was interrupted", e);
        }

        validateStatus(response);
        return extractContent(response.body());
    }

    private String buildRequestBody(String prompt) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL_ID);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_tokens", 3000);
        body.add("messages", messages);

        return body.toString();
    }

    private void validateStatus(HttpResponse<String> response) throws IOException {
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.error("validateStatus: API error. status={}", status);
            throw new IOException("Groq API error (HTTP " + status + "): " + response.body());
        }
    }

    private String extractContent(String responseBody) throws IOException {
        try {
            return JsonParser.parseString(responseBody)
                    .getAsJsonObject()
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (JsonParseException | IllegalStateException | IndexOutOfBoundsException e) {
            log.error("extractContent: failed to parse response. reason={}", e.getMessage(), e);
            throw new IOException("Failed to parse Groq API response: " + e.getMessage(), e);
        }
    }
}
