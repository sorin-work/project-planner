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
    void slipIsSignedAndCountsWorkingDaysOnly() {
        assertThat(cal.slip(MONDAY, MONDAY)).isZero();
        assertThat(cal.slip(MONDAY, FRIDAY)).isEqualTo(4);
        assertThat(cal.slip(FRIDAY, MONDAY)).isEqualTo(-4);
        assertThat(cal.slip(FRIDAY, MONDAY.plusWeeks(1))).isEqualTo(1);
        assertThat(cal.slip(MONDAY.plusWeeks(1), FRIDAY)).isEqualTo(-1);
        // Friday to Saturday: a day later on the calendar, no working day later.
        assertThat(cal.slip(FRIDAY, SATURDAY)).isZero();
        assertThat(cal.slip(SATURDAY, FRIDAY)).isZero();
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
}
