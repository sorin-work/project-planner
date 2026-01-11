package com.projectplanner.common;

import java.util.List;

/** Minimal RFC 4180 CSV writer. */
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
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
