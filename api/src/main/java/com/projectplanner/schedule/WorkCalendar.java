package com.projectplanner.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Working days are Monday to Friday. Task durations, lags and rescheduling are all
 * counted in working days, so a two day task that starts on Friday ends on Monday.
 */
public final class WorkCalendar {

    public static final WorkCalendar DEFAULT = new WorkCalendar();

    public boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** {@code date} itself if it is a working day, otherwise the next one. */
    public LocalDate onOrAfter(LocalDate date) {
        LocalDate d = date;
        while (!isWorkingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** {@code date} itself if it is a working day, otherwise the one before. */
    public LocalDate onOrBefore(LocalDate date) {
        LocalDate d = date;
        while (!isWorkingDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** The first working day strictly after {@code date}. */
    public LocalDate nextWorkingDay(LocalDate date) {
        return onOrAfter(date.plusDays(1));
    }

    /**
     * Moves {@code days} working days forward from {@code date} (or backwards when
     * negative). Zero returns {@code date} unchanged, even on a weekend.
     */
    public LocalDate addWorkingDays(LocalDate date, int days) {
        LocalDate d = date;
        int step = days >= 0 ? 1 : -1;
        int remaining = Math.abs(days);
        while (remaining > 0) {
            d = d.plusDays(step);
            if (isWorkingDay(d)) {
                remaining--;
            }
        }
        return d;
    }

    /** Working days from {@code start} to {@code end}, both included. Zero if end is before start. */
    public int workingDaysBetween(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        // Whole weeks first so long projects don't loop day by day.
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        long weeks = days / 7;
        count += (int) (weeks * 5);
        LocalDate d = start.plusDays(weeks * 7);
        while (!d.isAfter(end)) {
            if (isWorkingDay(d)) {
                count++;
            }
            d = d.plusDays(1);
        }
        return count;
    }

    /**
     * How far {@code actual} is from {@code planned} in working days: positive when it
     * is later, negative when it is earlier, zero for the same day and when only days
     * off lie in between.
     */
    public int slip(LocalDate planned, LocalDate actual) {
        if (actual.isAfter(planned)) {
            return workingDaysBetween(planned.plusDays(1), actual);
        }
        return actual.isBefore(planned) ? -workingDaysBetween(actual.plusDays(1), planned) : 0;
    }

    /** Duration of a task in working days; a task always takes at least one day. */
    public int duration(LocalDate start, LocalDate end) {
        return Math.max(1, workingDaysBetween(start, end));
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
