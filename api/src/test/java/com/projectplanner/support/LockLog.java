package com.projectplanner.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sees every statement Hibernate sends in the tests and, while a test records, keeps the
 * ones that lock or write a row of {@code memberships} or {@code projects}, in the order they
 * were sent. H2 cannot show a deadlock the way PostgreSQL does, but the order in which rows
 * are taken is what decides whether one can happen, and that order shows here.
 */
public class LockLog implements StatementInspector {

    private static final Pattern TABLE = Pattern.compile("\\b(memberships|projects)\\b");
    private static final List<String> taken = new ArrayList<>();
    private static volatile boolean recording;

    @Override
    public String inspect(String sql) {
        if (recording) {
            String kind = kind(sql.toLowerCase(Locale.ROOT));
            if (kind != null) {
                synchronized (taken) {
                    taken.add(kind);
                }
            }
        }
        return sql;
    }

    /** "memberships" or "projects" for a statement that locks or writes rows of one of them, otherwise null. */
    static String kind(String sql) {
        String trimmed = sql.strip();
        boolean write = trimmed.startsWith("update ") || trimmed.startsWith("delete ") || trimmed.startsWith("insert ");
        if (!write && !trimmed.contains(" for update")) {
            return null;
        }
        // The table that is locked or written: the first one named after the verb or after "from".
        Matcher matcher = TABLE.matcher(write ? trimmed : trimmed.substring(trimmed.indexOf(" from ")));
        return matcher.find() ? matcher.group(1) : null;
    }

    public static void start() {
        synchronized (taken) {
            taken.clear();
        }
        recording = true;
    }

    /** Stops recording and gives the tables in the order their rows were taken, each run of one table once. */
    public static List<String> stop() {
        recording = false;
        List<String> order = new ArrayList<>();
        synchronized (taken) {
            for (String table : taken) {
                if (order.isEmpty() || !order.getLast().equals(table)) {
                    order.add(table);
                }
            }
        }
        return order;
    }
}
