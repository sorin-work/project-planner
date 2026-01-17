package com.projectplanner.report;

import com.projectplanner.report.ProgressCalculator.Progress;
import com.projectplanner.schedule.WorkCalendar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProgressCalculatorTest {

    private final ProgressCalculator calculator = new ProgressCalculator(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    private static TaskFacts task(long id, double estimate, boolean done, LocalDate start, LocalDate due) {
        return new TaskFacts(id, null, estimate, done, start, due, false);
    }

    @Test
    void progressIsWeightedByEstimate() {
        Progress p = calculator.calculate(List.of(
                task(1, 30, true, null, null),
                task(2, 10, false, null, null)), day(0));
        assertThat(p.progressPct()).isEqualTo(75.0);
        assertThat(p.totalEstimateHours()).isEqualTo(40.0);
        assertThat(p.doneEstimateHours()).isEqualTo(30.0);
    }

    @Test
    void parentsWithSubtasksAreNotCountedTwice() {
        Progress p = calculator.calculate(List.of(
                task(1, 40, false, null, null),
                new TaskFacts(2, 1L, 10, true, null, null, false),
                new TaskFacts(3, 1L, 10, false, null, null, false)), day(0));
        assertThat(p.totalEstimateHours()).isEqualTo(20.0);
        assertThat(p.progressPct()).isEqualTo(50.0);
    }

    @Test
    void scheduleVarianceComparesEarnedWithPlanned() {
        // Task 1 should be finished by now but isn't; task 2 is done early.
        Progress p = calculator.calculate(List.of(
                task(1, 20, false, day(0), day(1)),
                task(2, 10, true, day(7), day(8))), day(3));
        assertThat(p.plannedHours()).isEqualTo(20.0);
        assertThat(p.earnedHours()).isEqualTo(10.0);
        assertThat(p.scheduleVarianceHours()).isEqualTo(-10.0);
        assertThat(p.spi()).isEqualTo(0.5);
    }

    @Test
    void plannedWorkGrowsLinearlyOverWorkingDays() {
        // Five day task, today is the end of day three.
        Progress p = calculator.calculate(List.of(task(1, 50, false, day(0), day(4))), day(2));
        assertThat(p.plannedHours()).isCloseTo(30.0, within(0.01));
        assertThat(p.plannedPct()).isCloseTo(60.0, within(0.01));
    }

    @Test
    void spiIsNullBeforeAnyWorkIsPlanned() {
        Progress p = calculator.calculate(List.of(task(1, 8, false, day(7), day(8))), day(0));
        assertThat(p.spi()).isNull();
        assertThat(p.scheduleVarianceHours()).isZero();
    }

    @Test
    void countsOverdueOpenTasks() {
        Progress p = calculator.calculate(List.of(
                task(1, 5, false, day(0), day(1)),
                task(2, 5, false, day(0), day(3)),
                task(3, 5, false, day(0), day(10))), day(3));
        assertThat(p.overdueTasks()).isEqualTo(1);
        assertThat(p.openTasks()).isEqualTo(3);
    }

    @Test
    void finishedTasksAreNeverOverdue() {
        Progress p = calculator.calculate(List.of(
                task(1, 5, true, day(0), day(1)),
                task(2, 5, false, day(0), day(1))), day(5));
        assertThat(p.overdueTasks()).isEqualTo(1);
        assertThat(p.doneTasks()).isEqualTo(1);
    }
}
