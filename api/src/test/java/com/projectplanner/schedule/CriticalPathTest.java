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
    void projectEndIsTheLatestFinishNotTheLastTaskInOrder() {
        // Two independent chains; the long one decides the end date.
        var a = new ScheduledTask(1, day(0), day(9));
        var b = new ScheduledTask(2, day(0), day(0));
        var c = new ScheduledTask(3, day(1), day(1));
        CriticalPath.Result result = cpm.calculate(List.of(a, b, c), List.of(new Link(2, 3)));
        assertThat(result.finish()).isEqualTo(day(9));
        assertThat(result.criticalTaskIds()).containsExactly(1L);
        CriticalPath.TaskTiming timingC = result.tasks().stream().filter(t -> t.taskId() == 3).findFirst().orElseThrow();
        assertThat(timingC.floatDays()).isEqualTo(6);
    }

    @Test
    void lagCountsTowardsThePath() {
        var a = new ScheduledTask(1, day(0), day(0));
        var b = new ScheduledTask(2, day(3), day(3));
        var c = new ScheduledTask(3, day(0), day(2));
        CriticalPath.Result result = cpm.calculate(List.of(a, b, c), List.of(new Link(1, 2, 2)));
        assertThat(result.criticalTaskIds()).containsExactly(1L, 2L);
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

    @Test
    void unscheduledTasksAreLeftOut() {
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, null, day(3));
        CriticalPath.Result result = cpm.calculate(List.of(a, b), List.of(new Link(1, 2)));
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.criticalTaskIds()).containsExactly(1L);
    }

    @Test
    void startToStartBranchesRunSideBySide() {
        // A Mon - Fri, B starts with A plus two days and takes three: Wed - Fri. Both end together, both critical.
        var a = new ScheduledTask(1, day(0), day(4));
        var b = new ScheduledTask(2, day(2), day(4));
        CriticalPath.Result result = cpm.calculate(List.of(a, b), List.of(new Link(1, 2, 2, DependencyType.SS)));
        assertThat(result.finish()).isEqualTo(day(4));
        assertThat(result.durationDays()).isEqualTo(5);
        assertThat(result.criticalTaskIds()).containsExactly(1L, 2L);
    }

    @Test
    void finishToFinishPushesTheSuccessorsFinishAndCountsTowardsTheEnd() {
        // A Mon - Fri the week after; B (two days) must end one day after A: Monday of the third week.
        var a = new ScheduledTask(1, day(7), day(11));
        var b = new ScheduledTask(2, day(0), day(1));
        CriticalPath.Result result = cpm.calculate(List.of(a, b), List.of(new Link(1, 2, 1, DependencyType.FF)));
        CriticalPath.TaskTiming timing = result.tasks().stream().filter(t -> t.taskId() == 2).findFirst().orElseThrow();
        assertThat(timing.earlyStart()).isEqualTo(day(11));
        assertThat(timing.earlyFinish()).isEqualTo(day(14));
        assertThat(result.finish()).isEqualTo(day(14));
        assertThat(result.criticalTaskIds()).containsExactly(1L, 2L);
    }

    @Test
    void startToFinishAndALeadGiveTheSameFloatBothWays() {
        // C ends Friday the 13th; A (Mon - Tue) waits for nothing, B must end no earlier than C starts.
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, day(0), day(1));
        var c = new ScheduledTask(3, day(7), day(11));
        CriticalPath.Result result = cpm.calculate(List.of(a, b, c),
                List.of(new Link(1, 3, -1), new Link(3, 2, 0, DependencyType.SF)));
        CriticalPath.TaskTiming late = result.tasks().stream().filter(t -> t.taskId() == 2).findFirst().orElseThrow();
        // B ends when C starts at the earliest: Friday to Monday the 9th.
        assertThat(late.earlyFinish()).isEqualTo(day(7));
        // The lead lets A end a day after C starts without moving C, so A has float.
        CriticalPath.TaskTiming first = result.tasks().stream().filter(t -> t.taskId() == 1).findFirst().orElseThrow();
        assertThat(first.floatDays()).isPositive();
        assertThat(result.finish()).isEqualTo(day(11));
    }
}
