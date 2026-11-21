package com.projectplanner.time;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The weeks that are approved, for the time that only counts once its week is: the
 * "approved only" filter of the reports and the export, and invoices. An entry counts when
 * the timesheet of its person and week is approved.
 */
public final class ApprovedWeeks {

    /** One person's approved days, first and last included. */
    public record Span(long userId, LocalDate from, LocalDate to) {
    }

    private final Set<String> weeks;
    private final List<Span> spans;

    private ApprovedWeeks(Set<String> weeks, List<Span> spans) {
        this.weeks = weeks;
        this.spans = spans;
    }

    /** The approved weeks of an organisation that touch {@code from} to {@code to}, in one query. */
    public static ApprovedWeeks of(long organisationId, LocalDate from, LocalDate to) {
        List<Timesheet> sheets = Timesheet.list("organisationId = ?1 and status = ?2 and weekStart between ?3 and ?4 order by userId, weekStart",
                organisationId, TimesheetStatus.APPROVED, from.minusDays(6), to);
        Set<String> weeks = sheets.stream().map(s -> key(s.userId, s.weekStart)).collect(Collectors.toSet());
        // Weeks in a row make one span, so that a query over a long range stays short.
        Map<Long, TreeSet<LocalDate>> byUser = new HashMap<>();
        sheets.forEach(s -> byUser.computeIfAbsent(s.userId, u -> new TreeSet<>()).add(s.weekStart));
        List<Span> spans = new ArrayList<>();
        byUser.forEach((userId, starts) -> {
            LocalDate first = null;
            LocalDate last = null;
            for (LocalDate start : starts) {
                if (last != null && start.equals(last.plusDays(1))) {
                    last = start.plusDays(6);
                    continue;
                }
                if (first != null) {
                    spans.add(new Span(userId, first, last));
                }
                first = start;
                last = start.plusDays(6);
            }
            if (first != null) {
                spans.add(new Span(userId, first, last));
            }
        });
        return new ApprovedWeeks(weeks, spans);
    }

    public boolean approved(long userId, LocalDate day) {
        return weeks.contains(key(userId, com.projectplanner.schedule.WorkCalendar.weekStart(day)));
    }

    public List<Span> spans() {
        return spans;
    }

    private static String key(long userId, LocalDate weekStart) {
        return userId + ":" + weekStart;
    }
}
