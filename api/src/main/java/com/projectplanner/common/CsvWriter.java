package com.projectplanner.common;

import java.util.List;

/**
 * Minimal RFC 4180 CSV writer. Values that a spreadsheet would treat as a formula
 * are prefixed with an apostrophe so exported descriptions can't run anything.
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder();

    public CsvWriter row(List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(values.get(i)));
        }
        out.append("\r\n");
        return this;
    }

    public CsvWriter row(Object... values) {
        return row(List.of(values));
    }

    @Override
    public String toString() {
        return out.toString();
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
