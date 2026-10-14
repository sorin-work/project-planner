package com.projectplanner.common;

public final class Text {

    private Text() {
    }

    /** Trimmed text, or null when there is nothing but whitespace. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
