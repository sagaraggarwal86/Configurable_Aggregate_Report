package com.personal.jmeter.ai;

import java.io.IOException;

/**
 * Thrown when an AI provider cannot be used — provider not found in configuration,
 * API key fails structural validation, or a live ping is rejected.
 *
 * <p>Extends {@link IOException} so callers that declare {@code throws IOException}
 * need no signature change. Typed subclass allows {@link com.personal.jmeter.cli.Main}
 * to map provider failures to {@code EXIT_AI_ERROR (3)} without string-matching
 * exception messages.</p>
 *
 * <p>Distinct from {@link AiServiceException}, which covers runtime API errors
 * (HTTP errors, empty responses, retry exhaustion) during report generation.
 * This exception covers configuration-time failures before any API call is made.</p>
 */
public class AiProviderException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a descriptive message.
     *
     * @param message the detail message
     */
    public AiProviderException(String message) {
        super(message);
    }

    /**
     * Constructs the exception with a descriptive message and a root cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
