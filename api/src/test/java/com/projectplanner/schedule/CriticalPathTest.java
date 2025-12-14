package com.projectplanner.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CriticalPathTest {

    private final CriticalPath cpm = new CriticalPath(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    @Test
    void emptyProject() {
        CriticalPath.Result result = cpm.calculate(List.of(), List.of());
        assertThat(result.criticalTaskIds()).isEmpty();
        assertThat(result.start()).isNull();
    }

    @Test
    void singleChainIsEntirelyCritical() {
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, day(2), day(4));
        CriticalPath.Result result = cpm.calculate(List.of(a, b), List.of(new Link(1, 2)));
        assertThat(result.criticalTaskIds()).containsExactly(1L, 2L);
        assertThat(result.durationDays()).isEqualTo(5);
        assertThat(result.finish()).isEqualTo(day(4));
    }

    @Test
    void shorterParallelBranchHasFloat() {
        // A (2 days) -> C, B (4 days) -> C. A can slip two days.
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, day(0), day(3));
        var c = new ScheduledTask(3, day(4), day(4));
        CriticalPath.Result result = cpm.calculate(List.of(a, b, c), List.of(new Link(1, 3), new Link(2, 3)));
        assertThat(result.criticalTaskIds()).containsExactly(2L, 3L);
        CriticalPath.TaskTiming timingA = result.tasks().stream().filter(t -> t.taskId() == 1).findFirst().orElseThrow();
        assertThat(timingA.floatDays()).isEqualTo(2);
        assertThat(timingA.lateFinish()).isEqualTo(day(3));
    }

    @Test
    void plannedStartActsAsStartNoEarlierThan() {
        // B could start right after A, but is planned three days later.
        var a = new ScheduledTask(1, day(0), day(0));
        var b = new ScheduledTask(2, day(4), day(4));
        CriticalPath.Result result = cpm.calculate(List.of(a, b), List.of(new Link(1, 2)));
        CriticalPath.TaskTiming timingA = result.tasks().getFirst();
        assertThat(timingA.taskId()).isEqualTo(1L);
        assertThat(timingA.floatDays()).isEqualTo(3);
        assertThat(result.criticalTaskIds()).containsExactly(2L);
    }
}
