package com.personal.jmeter.parser;

import java.io.IOException;

/**
 * Thrown when a JTL file cannot be parsed — file is empty, unreadable,
 * or contains no valid data rows.
 *
 * <p>Extends {@link IOException} so callers that declare {@code throws IOException}
 * need no signature change. Typed subclass allows {@link com.personal.jmeter.cli.Main}
 * to map parse failures to {@code EXIT_PARSE_ERROR (2)} without string-matching
 * exception messages.</p>
 */
public class JtlParseException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a descriptive message.
     *
     * @param message the detail message
     */
    public JtlParseException(String message) {
        super(message);
    }

    /**
     * Constructs the exception with a descriptive message and a root cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public JtlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
