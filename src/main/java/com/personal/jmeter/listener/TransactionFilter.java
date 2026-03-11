package com.personal.jmeter.listener;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stateless utility that decides whether a transaction label matches a
 * user-supplied search pattern.
 *
 * <ul>
 *   <li>Blank pattern always matches everything (no-filter state).</li>
 *   <li>Plain-text mode: case-insensitive substring match..</li>
 *   <li>RegEx mode: uses {@link Pattern}; invalid patterns never throw —
 *       they produce no matches. Compiled patterns are cached to avoid
 *       recompilation on every table row during live search.</li>
 * </ul>
 */
public final class TransactionFilter {

    /**
     * Maximum number of compiled patterns held in the cache before it is cleared.
     */
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * Thread-safe cache: regex string → compiled {@link Pattern}.
     * Avoids recompiling the same pattern on every keystroke / table row.
     */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE =
            new ConcurrentHashMap<>();

    private TransactionFilter() {
    }

    /**
     * Returns {@code true} when {@code label} satisfies the filter.
     *
     * @param label    transaction name to test (never {@code null})
     * @param pattern  search text; {@code null} or blank means "show everything"
     * @param useRegex {@code true} to treat {@code pattern} as a regex
     */
    public static boolean matches(String label, String pattern, boolean useRegex) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (useRegex) {
            return matchesRegex(label, pattern);
        }
        return label.toLowerCase().contains(pattern.toLowerCase());
    }

    /**
     * Compiles {@code pattern} once (then caches it) and tests via
     * {@link java.util.regex.Matcher#find()}.
     * Returns {@code false} on invalid patterns rather than propagating
     * {@link PatternSyntaxException} to the UI.
     */
    private static boolean matchesRegex(String label, String pattern) {
        Pattern compiled = PATTERN_CACHE.get(pattern);
        if (compiled == null) {
            try {
                compiled = Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                return false;
            }
            // Eviction strategy: clear the entire cache when the size limit is reached.
            // This is intentionally simple — in normal GUI use, the number of distinct
            // regex patterns entered by a user in one session is in the single digits,
            // so the limit of MAX_CACHE_SIZE (100) is effectively never reached.
            // A full clear is cheaper to reason about than LRU eviction and introduces
            // no correctness risk; only a one-time recompile cost if the limit is hit.
            if (PATTERN_CACHE.size() >= MAX_CACHE_SIZE) {
                PATTERN_CACHE.clear();
            }
            PATTERN_CACHE.put(pattern, compiled);
        }
        return compiled.matcher(label).find();
    }
}
