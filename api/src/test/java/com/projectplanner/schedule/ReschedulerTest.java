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
    void waitsForTheLatestOfSeveralPredecessors() {
        var a = new ScheduledTask(1, day(0), day(1));
        var b = new ScheduledTask(2, day(0), day(3));
        var c = new ScheduledTask(3, day(0), day(0));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b, c),
                List.of(new Link(1, 3), new Link(2, 3)), Set.of(1L));
        assertThat(moved.get(3L).start()).isEqualTo(day(4));
    }

    @Test
    void ignoresTasksWithoutDates() {
        var a = new ScheduledTask(1, day(0), day(4));
        var b = new ScheduledTask(2, null, null);
        var c = new ScheduledTask(3, day(1), day(1));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b, c),
                List.of(new Link(1, 2), new Link(2, 3)), Set.of(1L));
        assertThat(moved).isEmpty();
    }

    @Test
    void shiftsByWorkingDaysAndKeepsTheLength() {
        var task = new ScheduledTask(1, day(3), day(4)); // Thu - Fri
        // One day later is Fri - Mon, still two working days. Calendar days would have made it Fri - Sat.
        assertThat(rescheduler.shift(task, 1)).isEqualTo(new ScheduledTask(1, day(4), day(7)));
        assertThat(rescheduler.shift(task, 5)).isEqualTo(new ScheduledTask(1, day(10), day(11)));
        assertThat(rescheduler.shift(task, -4)).isEqualTo(new ScheduledTask(1, day(-3), day(0))); // Fri - Mon
    }

    @Test
    void aMondayStartShiftedEarlierLandsOnFridayAndStaysThere() {
        var task = new ScheduledTask(1, day(7), day(8)); // Mon - Tue
        // Pulling the result onto a working day would put it back on Monday and the bar would never move.
        assertThat(rescheduler.shift(task, -1)).isEqualTo(new ScheduledTask(1, day(4), day(7))); // Fri - Mon
        assertThat(rescheduler.shift(task, -2)).isEqualTo(new ScheduledTask(1, day(3), day(4))); // Thu - Fri
    }

    @Test
    void shiftsOutOfAWeekendIntoTheNextWorkingDay() {
        var task = new ScheduledTask(1, day(5), day(8)); // Sat - Tue, two working days
        assertThat(rescheduler.shift(task, 1)).isEqualTo(new ScheduledTask(1, day(7), day(8))); // Mon - Tue
        assertThat(rescheduler.shift(task, -1)).isEqualTo(new ScheduledTask(1, day(4), day(7))); // Fri - Mon
    }

    @Test
    void shiftsTheOnlyDateATaskHasAndLeavesEverythingAloneForZero() {
        assertThat(rescheduler.shift(new ScheduledTask(1, day(4), null), 1)).isEqualTo(new ScheduledTask(1, day(7), null));
        assertThat(rescheduler.shift(new ScheduledTask(1, null, day(7)), -1)).isEqualTo(new ScheduledTask(1, null, day(4)));
        assertThat(rescheduler.shift(new ScheduledTask(1, day(4), day(4)), 2)).isEqualTo(new ScheduledTask(1, day(8), day(8)));
        // Thu - Sat is two working days. Recomputing its due date would move it to Friday.
        var odd = new ScheduledTask(1, day(3), day(5));
        assertThat(rescheduler.shift(odd, 0)).isEqualTo(odd);
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

    @Test
    void startToStartPushesTheSuccessorWhenThePredecessorStartsLater() {
        // A moved to start on Wednesday; B, which starts with it plus one day, started on Tuesday.
        var a = new ScheduledTask(1, day(2), day(9));
        var b = new ScheduledTask(2, day(1), day(2));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, 1, DependencyType.SS)), Set.of(1L));
        assertThat(moved.get(2L)).isEqualTo(new ScheduledTask(2, day(3), day(4)));
    }

    @Test
    void startToStartLeavesASuccessorAloneThatOverlapsOnPurpose() {
        // B runs alongside A, which is what start-to-start is for: nothing to push.
        var a = new ScheduledTask(1, day(0), day(4));
        var b = new ScheduledTask(2, day(1), day(3));
        assertThat(rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, 0, DependencyType.SS)), Set.of(1L))).isEmpty();
    }

    @Test
    void finishToFinishMovesTheSuccessorWholeSoThatItsFinishFits() {
        // A now ends on Tuesday the 10th. B (Mon - Wed, three days) may not end before it: Friday to Tuesday.
        var a = new ScheduledTask(1, day(0), day(8));
        var b = new ScheduledTask(2, day(0), day(2));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, 0, DependencyType.FF)), Set.of(1L));
        assertThat(moved.get(2L)).isEqualTo(new ScheduledTask(2, day(4), day(8)));
    }

    @Test
    void startToFinishHoldsTheFinishAgainstTheStart() {
        // A starts on Thursday; B may not end before then, so the two-day B runs Wednesday to Thursday.
        var a = new ScheduledTask(1, day(3), day(9));
        var b = new ScheduledTask(2, day(0), day(1));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, 0, DependencyType.SF)), Set.of(1L));
        assertThat(moved.get(2L)).isEqualTo(new ScheduledTask(2, day(2), day(3)));
    }

    @Test
    void aLeadLetsTheSuccessorStartBeforeThePredecessorEnds() {
        // A ends on Friday. With two days of lead B may start on Thursday, so a Tuesday start is pushed to Thursday only.
        var a = new ScheduledTask(1, day(0), day(4));
        var b = new ScheduledTask(2, day(1), day(2));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b), List.of(new Link(1, 2, -2)), Set.of(1L));
        assertThat(moved.get(2L)).isEqualTo(new ScheduledTask(2, day(3), day(4)));
        // Already on Thursday: left alone.
        var later = new ScheduledTask(2, day(3), day(4));
        assertThat(rescheduler.propagate(List.of(a, later), List.of(new Link(1, 2, -2)), Set.of(1L))).isEmpty();
    }

    @Test
    void cascadesThroughAChainOfMixedTypes() {
        // A -FS-> B -SS+1-> C -FF-> D. A slips a week and everything follows.
        var a = new ScheduledTask(1, day(7), day(11));
        var b = new ScheduledTask(2, day(7), day(8));
        var c = new ScheduledTask(3, day(8), day(9));
        var d = new ScheduledTask(4, day(8), day(9));
        List<Link> links = List.of(new Link(1, 2), new Link(2, 3, 1, DependencyType.SS), new Link(3, 4, 0, DependencyType.FF));
        Map<Long, ScheduledTask> moved = rescheduler.propagate(List.of(a, b, c, d), links, Set.of(1L));
        assertThat(moved.get(2L)).isEqualTo(new ScheduledTask(2, day(14), day(15)));
        assertThat(moved.get(3L)).isEqualTo(new ScheduledTask(3, day(15), day(16)));
        assertThat(moved.get(4L)).isEqualTo(new ScheduledTask(4, day(15), day(16)));
    }
}
