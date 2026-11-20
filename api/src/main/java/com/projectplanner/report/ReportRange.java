package com.projectplanner.report;

import com.projectplanner.common.ApiException;
import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * The days a report covers, both included. Either one of the presets, worked out
 * from the organisation's date, or a pair of dates.
 */
public record ReportRange(LocalDate from, LocalDate to) {

    /** A longer range than this is refused, as it is for the CSV export of entries. */
    public static final int MAX_YEARS = 2;

    public enum Preset {
        /** Monday to Sunday of the current week. */
        THIS_WEEK,
        LAST_WEEK,
        /** The first to the last day of the current month. */
        THIS_MONTH,
        LAST_MONTH;

        static Preset parse(String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_preset", "Unknown preset " + text
                        + ". Use this_week, last_week, this_month or last_month");
            }
        }
    }

    public static ReportRange of(Preset preset, LocalDate today) {
        LocalDate monday = WorkCalendar.weekStart(today);
        LocalDate first = today.withDayOfMonth(1);
        return switch (preset) {
            case THIS_WEEK -> new ReportRange(monday, monday.plusDays(6));
            case LAST_WEEK -> new ReportRange(monday.minusWeeks(1), monday.minusDays(1));
            case THIS_MONTH -> new ReportRange(first, today.with(TemporalAdjusters.lastDayOfMonth()));
            case LAST_MONTH -> new ReportRange(first.minusMonths(1), first.minusDays(1));
        };
    }

    /**
     * What a request asks for: the preset if it names one, otherwise its two dates.
     * {@code today} is the organisation's day.
     */
    public static ReportRange resolve(String preset, LocalDate from, LocalDate to, LocalDate today) {
        if (preset != null && !preset.isBlank()) {
            return of(Preset.parse(preset), today);
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "Pass a preset, or a from and to date with to on or after from");
        }
        if (from.plusYears(MAX_YEARS).isBefore(to)) {
            throw ApiException.badRequest("invalid_range", "A report covers at most two years");
        }
        return new ReportRange(from, to);
    }
}
