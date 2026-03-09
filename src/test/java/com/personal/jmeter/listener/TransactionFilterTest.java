package com.personal.jmeter.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TransactionFilter}.
 *
 * <p>Organised into three nested classes:
 * <ul>
 *   <li>{@link BlankPatternTests}  — no filter = match everything</li>
 *   <li>{@link PlainTextTests}     — case-insensitive substring match</li>
 *   <li>{@link RegexTests}         — full-string regex match + error safety</li>
 * </ul>
 */
@DisplayName("TransactionFilter")
class TransactionFilterTest {

    // ─────────────────────────────────────────────────────────────
    // Blank / null pattern → always match
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank pattern (no filter active)")
    class BlankPatternTests {

        @Test
        @DisplayName("null pattern matches any label")
        void nullPatternMatchesAll() {
            assertTrue(TransactionFilter.matches("Login", null, false));
            assertTrue(TransactionFilter.matches("Login", null, true));
        }

        @Test
        @DisplayName("empty string pattern matches any label")
        void emptyPatternMatchesAll() {
            assertTrue(TransactionFilter.matches("Login", "", false));
            assertTrue(TransactionFilter.matches("Login", "", true));
        }

        @ParameterizedTest
        @ValueSource(strings = {"  ", "\t", "   "})
        @DisplayName("whitespace-only pattern matches any label")
        void whitespaceOnlyMatchesAll(String pattern) {
            assertTrue(TransactionFilter.matches("Checkout", pattern, false));
            assertTrue(TransactionFilter.matches("Checkout", pattern, true));
        }

        @Test
        @DisplayName("blank pattern matches empty label too")
        void blankPatternMatchesEmptyLabel() {
            assertTrue(TransactionFilter.matches("", "", false));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Plain-text mode
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plain-text mode (case-insensitive substring)")
    class PlainTextTests {

        @Test
        @DisplayName("exact match")
        void exactMatch() {
            assertTrue(TransactionFilter.matches("Login", "Login", false));
        }

        @Test
        @DisplayName("case-insensitive match — upper pattern")
        void caseInsensitiveUpperPattern() {
            assertTrue(TransactionFilter.matches("Login Flow", "LOGIN", false));
        }

        @Test
        @DisplayName("case-insensitive match — upper label")
        void caseInsensitiveUpperLabel() {
            assertTrue(TransactionFilter.matches("CHECKOUT", "checkout", false));
        }

        @Test
        @DisplayName("substring match — pattern inside label")
        void substringMatch() {
            assertTrue(TransactionFilter.matches("Homepage Load", "Load", false));
        }

        @Test
        @DisplayName("substring match — beginning of label")
        void prefixMatch() {
            assertTrue(TransactionFilter.matches("AddToCart Request", "AddToCart", false));
        }

        @Test
        @DisplayName("no match when pattern not in label")
        void noMatch() {
            assertFalse(TransactionFilter.matches("Login", "checkout", false));
        }

        @Test
        @DisplayName("no match for empty label with non-blank pattern")
        void emptyLabelNoMatch() {
            assertFalse(TransactionFilter.matches("", "login", false));
        }

        @Test
        @DisplayName("partial label match returns true")
        void partialLabelMatchReturnsTrue() {
            assertTrue(TransactionFilter.matches("Search-API-v2", "API", false));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Regex mode
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RegEx mode")
    class RegexTests {

        @Test
        @DisplayName("simple regex prefix match")
        void regexPrefixMatch() {
            assertTrue(TransactionFilter.matches("Login-001", "Login.*", true));
        }

        @Test
        @DisplayName("digit pattern matches numeric suffix")
        void digitPattern() {
            assertTrue(TransactionFilter.matches("Request-42", ".*-\\d+", true));
        }

        @Test
        @DisplayName("alternation — matches first alternative")
        void alternationFirstBranch() {
            assertTrue(TransactionFilter.matches("Login", "Login|Checkout", true));
        }

        @Test
        @DisplayName("alternation — matches second alternative")
        void alternationSecondBranch() {
            assertTrue(TransactionFilter.matches("Checkout", "Login|Checkout", true));
        }

        @Test
        @DisplayName("regex no match")
        void regexNoMatch() {
            assertFalse(TransactionFilter.matches("Checkout", "Login.*", true));
        }

        @Test
        @DisplayName("invalid regex returns false without throwing")
        void invalidRegexReturnsFalse() {
            assertFalse(TransactionFilter.matches("Login", "[invalid", true));
        }

        @Test
        @DisplayName("another invalid regex (unclosed group) returns false")
        void unclosedGroupReturnsFalse() {
            assertFalse(TransactionFilter.matches("Login", "(unclosed", true));
        }

        @Test
        @DisplayName("case-sensitive by default — no match on wrong case")
        void caseSensitiveNoMatch() {
            assertFalse(TransactionFilter.matches("Login", "login", true));
        }

        @Test
        @DisplayName("case-insensitive regex with (?i) flag")
        void caseInsensitiveFlagWorks() {
            assertTrue(TransactionFilter.matches("Login", "(?i)login", true));
        }

        @Test
        @DisplayName("dot-star matches any label")
        void dotStarMatchesAll() {
            assertTrue(TransactionFilter.matches("Any Transaction Name!", ".*", true));
        }

        @Test
        @DisplayName("partial find — pattern does not need to cover whole string")
        void partialFindInMiddle() {
            // Pattern.find() (not matches()) — so "API" should match "Search-API-v2"
            assertTrue(TransactionFilter.matches("Search-API-v2", "API", true));
        }
    }
}