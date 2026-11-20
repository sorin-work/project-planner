package com.projectplanner.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WorkCalendarTest {

    private final WorkCalendar cal = WorkCalendar.DEFAULT;

    // 2026-03-02 is a Monday.
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 3, 6);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 3, 7);

    @Test
    void weekendsAreNotWorkingDays() {
        assertThat(cal.isWorkingDay(MONDAY)).isTrue();
        assertThat(cal.isWorkingDay(FRIDAY)).isTrue();
        assertThat(cal.isWorkingDay(SATURDAY)).isFalse();
        assertThat(cal.isWorkingDay(SATURDAY.plusDays(1))).isFalse();
    }

    @Test
    void nextWorkingDaySkipsTheWeekend() {
        assertThat(cal.nextWorkingDay(MONDAY)).isEqualTo(MONDAY.plusDays(1));
        assertThat(cal.nextWorkingDay(FRIDAY)).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(cal.nextWorkingDay(SATURDAY)).isEqualTo(MONDAY.plusWeeks(1));
    }

    @Test
    void onOrAfterKeepsWorkingDays() {
        assertThat(cal.onOrAfter(FRIDAY)).isEqualTo(FRIDAY);
        assertThat(cal.onOrAfter(SATURDAY)).isEqualTo(MONDAY.plusWeeks(1));
    }

    @Test
    void onOrBeforeKeepsWorkingDays() {
        assertThat(cal.onOrBefore(MONDAY)).isEqualTo(MONDAY);
        assertThat(cal.onOrBefore(SATURDAY)).isEqualTo(FRIDAY);
        assertThat(cal.onOrBefore(SATURDAY.plusDays(1))).isEqualTo(FRIDAY);
    }

    @Test
    void addWorkingDaysCountsOnlyWeekdays() {
        assertThat(cal.addWorkingDays(MONDAY, 0)).isEqualTo(MONDAY);
        assertThat(cal.addWorkingDays(MONDAY, 4)).isEqualTo(FRIDAY);
        assertThat(cal.addWorkingDays(FRIDAY, 1)).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(cal.addWorkingDays(MONDAY, 10)).isEqualTo(MONDAY.plusWeeks(2));
        assertThat(cal.addWorkingDays(MONDAY.plusWeeks(1), -1)).isEqualTo(FRIDAY);
    }

    @Test
    void workingDaysBetweenIncludesBothEnds() {
        assertThat(cal.workingDaysBetween(MONDAY, MONDAY)).isEqualTo(1);
        assertThat(cal.workingDaysBetween(MONDAY, FRIDAY)).isEqualTo(5);
        assertThat(cal.workingDaysBetween(MONDAY, SATURDAY.plusDays(1))).isEqualTo(5);
        assertThat(cal.workingDaysBetween(FRIDAY, MONDAY.plusWeeks(1))).isEqualTo(2);
        assertThat(cal.workingDaysBetween(SATURDAY, SATURDAY.plusDays(1))).isZero();
        assertThat(cal.workingDaysBetween(FRIDAY, MONDAY)).isZero();
    }

    @Test
    void workingDaysBetweenOverLongRanges() {
        assertThat(cal.workingDaysBetween(MONDAY, MONDAY.plusWeeks(52).minusDays(1))).isEqualTo(260);
        assertThat(cal.workingDaysBetween(MONDAY.plusDays(2), MONDAY.plusWeeks(3).plusDays(1))).isEqualTo(15);
    }

    @Test
    void durationIsAtLeastOneDay() {
        assertThat(cal.duration(SATURDAY, SATURDAY)).isEqualTo(1);
        assertThat(cal.duration(MONDAY, FRIDAY)).isEqualTo(5);
    }

    @Test
    void weekStartIsMonday() {
        assertThat(WorkCalendar.weekStart(MONDAY)).isEqualTo(MONDAY);
        assertThat(WorkCalendar.weekStart(SATURDAY.plusDays(1))).isEqualTo(MONDAY);
        assertThat(WorkCalendar.weekStart(MONDAY.minusDays(1))).isEqualTo(MONDAY.minusWeeks(1));
    }

    @Test
    void holidaysAreLeftOutOfEveryCount() {
        // Wednesday is a holiday, and so is the Saturday, which changes nothing.
        WorkCalendar holidays = new WorkCalendar(java.util.List.of(MONDAY.plusDays(2), SATURDAY));
        assertThat(holidays.isWorkingDay(MONDAY.plusDays(2))).isFalse();
        assertThat(holidays.isHoliday(MONDAY.plusDays(2))).isTrue();
        assertThat(holidays.isHoliday(SATURDAY)).isFalse();
        assertThat(holidays.workingDaysBetween(MONDAY, FRIDAY)).isEqualTo(4);
        assertThat(holidays.workingDaysBetween(MONDAY, SATURDAY.plusDays(1))).isEqualTo(4);
        // The fast path for whole weeks takes the holidays off as well.
        assertThat(holidays.workingDaysBetween(MONDAY.minusWeeks(3), FRIDAY.plusWeeks(3))).isEqualTo(34);
        assertThat(holidays.workingDaysBetween(MONDAY.plusDays(3), FRIDAY.plusWeeks(1))).isEqualTo(7);
        // Two working days from Tuesday land on Friday, and a task that ends on the holiday moves past it.
        assertThat(holidays.addWorkingDays(MONDAY.plusDays(1), 2)).isEqualTo(FRIDAY);
        assertThat(holidays.addWorkingDays(FRIDAY, -2)).isEqualTo(MONDAY.plusDays(1));
        assertThat(holidays.onOrAfter(MONDAY.plusDays(2))).isEqualTo(MONDAY.plusDays(3));
        assertThat(holidays.duration(MONDAY, MONDAY.plusDays(2))).isEqualTo(2);
        // Without holidays nothing changes.
        assertThat(cal.workingDaysBetween(MONDAY, FRIDAY)).isEqualTo(5);
    }
}
