package com.projectplanner.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReschedulerTest {

    private final Rescheduler rescheduler = new Rescheduler(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    @Test
    void pushesSuccessorThatNowStartsTooEarly() {
        // A moved to finish on Wednesday; B started on Tuesday.
        var a = new ScheduledTask(1, day(0), day(2));
        var b = new ScheduledTask(2, day(1), day(3));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2)), Set.of(1L));
        assertThat(moved).containsOnlyKeys(2L);
        assertThat(moved.get(2L).start()).isEqualTo(day(3));
        assertThat(moved.get(2L).due()).isEqualTo(day(7)); // three working days: Thu, Fri, Mon
    }

    @Test
    void keepsDurationInWorkingDaysAcrossWeekends() {
        var a = new ScheduledTask(1, day(0), day(4)); // Mon - Fri
        var b = new ScheduledTask(2, day(2), day(4)); // Wed - Fri, 3 days
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2)), Set.of(1L));
        assertThat(moved.get(2L).start()).isEqualTo(day(7)); // next Monday
        assertThat(moved.get(2L).due()).isEqualTo(day(9)); // Wednesday
    }

    @Test
    void leavesSuccessorsThatAlreadyStartLateEnough() {
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, day(7), day(8));
        assertThat(rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2)), Set.of(1L))).isEmpty();
    }

    @Test
    void appliesLagInWorkingDays() {
        var a = new ScheduledTask(1, day(0), day(3)); // ends Thursday
        var b = new ScheduledTask(2, day(1), day(1));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, 2)), Set.of(1L));
        // Next working day is Friday, plus two working days of lag: Tuesday.
        assertThat(moved.get(2L).start()).isEqualTo(day(8));
    }

    @Test
    void cascadesThroughTheWholeChain() {
        var a = new ScheduledTask(1, day(0), day(4));
        var b = new ScheduledTask(2, day(1), day(1));
        var c = new ScheduledTask(3, day(2), day(2));
        var d = new ScheduledTask(4, day(3), day(3));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b, c, d),
                List.of(new Link(1, 2), new Link(2, 3), new Link(3, 4)), Set.of(1L));
        assertThat(moved).containsOnlyKeys(2L, 3L, 4L);
        assertThat(moved.get(2L).start()).isEqualTo(day(7));
        assertThat(moved.get(3L).start()).isEqualTo(day(8));
        assertThat(moved.get(4L).start()).isEqualTo(day(9));
    }

    @Test
    void doesNotTouchTasksOutsideTheMovedTasksChain() {
        // C already violates its link to D, but nothing upstream of it moved.
        var a = new ScheduledTask(1, day(0), day(0));
        var b = new ScheduledTask(2, day(7), day(7));
        var c = new ScheduledTask(3, day(0), day(4));
        var d = new ScheduledTask(4, day(1), day(1));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b, c, d),
                List.of(new Link(1, 2), new Link(3, 4)), Set.of(1L));
        assertThat(moved).isEmpty();
    }
}
