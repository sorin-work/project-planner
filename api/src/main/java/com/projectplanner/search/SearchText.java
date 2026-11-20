package com.projectplanner.search;

import java.util.Locale;

/**
 * What search does with text: the patterns for {@code LIKE} and the excerpt shown with
 * a hit. Matching is plain "contains", without regard to case. That is all H2 and
 * PostgreSQL have in common.
 */
public final class SearchText {

    /** Stands before a character of the search text that would otherwise be a wildcard. */
    public static final char ESCAPE = '!';

    private static final int SNIPPET_BEFORE = 30;
    private static final int SNIPPET_AFTER = 90;

    private SearchText() {
    }

    /** The text as it is matched: without the space around it, in lower case. */
    public static String normalise(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    /** A pattern that matches wherever the needle occurs. A % or _ in the needle matches itself. */
    public static String contains(String needle) {
        return "%" + escape(needle) + "%";
    }

    /** A pattern that matches where the text starts with the needle. */
    public static String startsWith(String needle) {
        return escape(needle) + "%";
    }

    static String escape(String needle) {
        StringBuilder escaped = new StringBuilder(needle.length() + 4);
        for (char c : needle.toCharArray()) {
            if (c == '%' || c == '_' || c == ESCAPE) {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * The part of the text around the first place the needle occurs, on one line, with an
     * ellipsis where something was cut off. Null when the needle is not in the text.
     */
    public static String snippet(String text, String needle) {
        if (text == null || needle.isEmpty()) {
            return null;
        }
        // The needle is flattened the same way, or a needle with two spaces in a row would
        // match the text and never the flattened text.
        String flat = text.strip().replaceAll("\\s+", " ");
        needle = needle.replaceAll("\\s+", " ");
        int at = flat.toLowerCase(Locale.ROOT).indexOf(needle);
        if (at < 0) {
            return null;
        }
        int from = Math.max(0, at - SNIPPET_BEFORE);
        int to = Math.min(flat.length(), at + needle.length() + SNIPPET_AFTER);
        // Not in the middle of a word, where a space is near enough.
        if (from > 0) {
            int space = flat.indexOf(' ', from);
            from = space >= 0 && space < at ? space + 1 : from;
        }
        if (to < flat.length()) {
            int space = flat.lastIndexOf(' ', to);
            to = space > at + needle.length() ? space : to;
        }
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }
}
