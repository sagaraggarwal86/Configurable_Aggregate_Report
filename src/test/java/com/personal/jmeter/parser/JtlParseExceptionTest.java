package com.personal.jmeter.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JtlParseException}.
 *
 * <p>No file system, no network, no Swing — verifies constructor contracts,
 * message/cause propagation, and type hierarchy.</p>
 */
@DisplayName("JtlParseException")
class JtlParseExceptionTest {

    // ─────────────────────────────────────────────────────────────
    // Type hierarchy
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type hierarchy")
    class TypeHierarchyTests {

        @Test
        @DisplayName("is an instance of IOException")
        void isInstanceOfIOException() {
            assertInstanceOf(IOException.class, new JtlParseException("msg"));
        }

        @Test
        @DisplayName("is an instance of Exception")
        void isInstanceOfException() {
            assertInstanceOf(Exception.class, new JtlParseException("msg"));
        }

        @Test
        @DisplayName("can be caught as IOException")
        void canBeCaughtAsIOException() {
            assertDoesNotThrow(() -> {
                try {
                    throw new JtlParseException("test");
                } catch (IOException e) {
                    // expected — type check passes
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Message constructor
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Message constructor")
    class MessageConstructorTests {

        @Test
        @DisplayName("getMessage returns the supplied message")
        void getMessageReturnsSuppliedMessage() {
            JtlParseException ex = new JtlParseException("JTL file is empty");
            assertEquals("JTL file is empty", ex.getMessage());
        }

        @Test
        @DisplayName("getCause returns null when only message is supplied")
        void getCauseIsNullWithMessageOnly() {
            JtlParseException ex = new JtlParseException("msg");
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("null message is accepted without throwing")
        void nullMessageAccepted() {
            assertDoesNotThrow(() -> new JtlParseException((String) null));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Message + cause constructor
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Message + cause constructor")
    class MessageCauseConstructorTests {

        @Test
        @DisplayName("getMessage returns the supplied message")
        void getMessageReturnsSuppliedMessage() {
            RuntimeException cause = new RuntimeException("root cause");
            JtlParseException ex = new JtlParseException("parse failed", cause);
            assertEquals("parse failed", ex.getMessage());
        }

        @Test
        @DisplayName("getCause returns the supplied cause")
        void getCauseReturnsSuppliedCause() {
            RuntimeException cause = new RuntimeException("root cause");
            JtlParseException ex = new JtlParseException("msg", cause);
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("null cause is accepted without throwing")
        void nullCauseAccepted() {
            assertDoesNotThrow(() -> new JtlParseException("msg", null));
        }

        @Test
        @DisplayName("cause message is accessible via getCause().getMessage()")
        void causeMessageAccessible() {
            RuntimeException cause = new RuntimeException("disk read error");
            JtlParseException ex = new JtlParseException("parse failed", cause);
            assertEquals("disk read error", ex.getCause().getMessage());
        }
    }
}
