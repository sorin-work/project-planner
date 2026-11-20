package com.projectplanner.schedule;

import com.projectplanner.schedule.BaselineComparison.Change;
import com.projectplanner.schedule.BaselineComparison.DateChange;
import com.projectplanner.schedule.BaselineComparison.Entry;
import com.projectplanner.schedule.BaselineComparison.Result;
import com.projectplanner.schedule.BaselineComparison.TaskChange;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineComparisonTest {

    private final BaselineComparison comparison = new BaselineComparison(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    private static Entry task(long id, LocalDate start, LocalDate due) {
        return new Entry(id, null, "Task " + id, start, due, 8, false);
    }

    private TaskChange one(Entry then, Entry now) {
        return comparison.compare(then == null ? List.of() : List.of(then), now == null ? List.of() : List.of(now), null, null).tasks().getFirst();
    }

    @Test
    void aTaskThatIsWhereItWasIsUnchanged() {
        TaskChange change = one(task(1, day(0), day(4)), task(1, day(0), day(4)));
        assertThat(change.change()).isEqualTo(Change.UNCHANGED);
        assertThat(change.startSlipDays()).isZero();
        assertThat(change.finishSlipDays()).isZero();
        assertThat(change.existedThen()).isTrue();
        assertThat(change.existsNow()).isTrue();
    }

    @Test
    void countsASlipInWorkingDays() {
        // Mon - Fri moved to Thu - Wed: three working days later at both ends, five on the calendar at the finish.
        TaskChange change = one(task(1, day(0), day(4)), task(1, day(3), day(9)));
        assertThat(change.change()).isEqualTo(Change.SLIPPED);
        assertThat(change.startSlipDays()).isEqualTo(3);
        assertThat(change.finishSlipDays()).isEqualTo(3);
        assertThat(change.baselineStart()).isEqualTo(day(0));
        assertThat(change.baselineDue()).isEqualTo(day(4));
        assertThat(change.currentStart()).isEqualTo(day(3));
        assertThat(change.currentDue()).isEqualTo(day(9));
    }

    @Test
    void countsATaskThatWasPulledIn() {
        TaskChange change = one(task(1, day(7), day(11)), task(1, day(4), day(8)));
        assertThat(change.change()).isEqualTo(Change.PULLED_IN);
        assertThat(change.startSlipDays()).isEqualTo(-1);
        assertThat(change.finishSlipDays()).isEqualTo(-3);
    }

    @Test
    void theFinishDecidesAndTheStartOnlyWhenTheFinishDidNotMove() {
        // Longer: starts when it did, ends two days later.
        assertThat(one(task(1, day(0), day(2)), task(1, day(0), day(4))).change()).isEqualTo(Change.SLIPPED);
        // Squeezed: starts two days later, ends when it was going to.
        TaskChange squeezed = one(task(1, day(0), day(4)), task(1, day(2), day(4)));
        assertThat(squeezed.change()).isEqualTo(Change.SLIPPED);
        assertThat(squeezed.startSlipDays()).isEqualTo(2);
        assertThat(squeezed.finishSlipDays()).isZero();
        // Starts later and still ends earlier: it was pulled in.
        assertThat(one(task(1, day(0), day(4)), task(1, day(1), day(3))).change()).isEqualTo(Change.PULLED_IN);
        // Starts earlier, ends when it was going to.
        assertThat(one(task(1, day(2), day(4)), task(1, day(0), day(4))).change()).isEqualTo(Change.PULLED_IN);
    }

    @Test
    void aDateOnADayOffIsTheWorkingDayItStandsFor() {
        // Due on Friday then, on Saturday now: no working day later.
        TaskChange weekendDue = one(task(1, day(0), day(4)), task(1, day(0), day(5)));
        assertThat(weekendDue.change()).isEqualTo(Change.UNCHANGED);
        assertThat(weekendDue.finishSlipDays()).isZero();
        // Started on Saturday then, on Monday now: the same working day. Monday to Tuesday is one.
        assertThat(one(task(1, day(5), day(9)), task(1, day(7), day(9))).startSlipDays()).isZero();
        assertThat(one(task(1, day(5), day(9)), task(1, day(8), day(9))).startSlipDays()).isEqualTo(1);
        // Due on Monday now instead of Friday: one working day, whatever lies in between.
        assertThat(one(task(1, day(0), day(4)), task(1, day(0), day(7))).finishSlipDays()).isEqualTo(1);
    }

    @Test
    void aTaskCreatedSinceIsAddedAndNotOnTime() {
        TaskChange change = one(null, task(2, day(0), day(1)));
        assertThat(change.change()).isEqualTo(Change.ADDED);
        assertThat(change.existedThen()).isFalse();
        assertThat(change.startSlipDays()).isNull();
        assertThat(change.finishSlipDays()).isNull();
        assertThat(change.baselineStart()).isNull();
    }

    @Test
    void aTaskThatHadNoDatesThenAndHasThemNowIsAddedToo() {
        TaskChange change = one(task(1, null, null), task(1, day(0), day(1)));
        assertThat(change.change()).isEqualTo(Change.ADDED);
        assertThat(change.existedThen()).isTrue();
        assertThat(change.finishSlipDays()).isNull();
    }

    @Test
    void aDeletedTaskIsRemovedAndKeepsWhatTheBaselineKnewOfIt() {
        TaskChange change = one(new Entry(1, null, "Old title", day(0), day(4), 8, true), null);
        assertThat(change.change()).isEqualTo(Change.REMOVED);
        assertThat(change.existsNow()).isFalse();
        assertThat(change.title()).isEqualTo("Old title");
        assertThat(change.milestone()).isTrue();
        assertThat(change.baselineDue()).isEqualTo(day(4));
        assertThat(change.currentDue()).isNull();
    }

    @Test
    void aTaskWhoseDatesWereClearedIsRemovedFromThePlanButStillThere() {
        TaskChange change = one(task(1, day(0), day(4)), task(1, null, null));
        assertThat(change.change()).isEqualTo(Change.REMOVED);
        assertThat(change.existsNow()).isTrue();
    }

    @Test
    void tasksWithoutDatesOnEitherSideHaveNothingToCompare() {
        assertThat(one(task(1, null, null), task(1, null, null)).change()).isEqualTo(Change.UNCHANGED);
        // A task that was deleted and never had dates is not a change to the plan's dates either.
        assertThat(one(task(1, null, null), null).change()).isEqualTo(Change.UNCHANGED);
        assertThat(one(null, task(1, null, null)).change()).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void aTaskWithOneDateIsComparedAsTheOneDayItIs() {
        // Due on Wednesday only, then given a start: starts two days earlier, ends the same day.
        TaskChange change = one(task(1, null, day(2)), task(1, day(0), day(2)));
        assertThat(change.startSlipDays()).isEqualTo(-2);
        assertThat(change.finishSlipDays()).isZero();
        assertThat(change.change()).isEqualTo(Change.PULLED_IN);
        assertThat(one(task(1, day(1), null), task(1, day(3), null)).finishSlipDays()).isEqualTo(2);
    }

    @Test
    void takesTheCurrentTitleAndUsesItForATaskThatWasRenamed() {
        TaskChange change = one(new Entry(1, null, "Draft", day(0), day(1), 8, false), new Entry(1, null, "Final", day(0), day(1), 8, false));
        assertThat(change.title()).isEqualTo("Final");
    }

    @Test
    void comparesTheEndOfTheProjectAndTheEndOfThePlan() {
        List<Entry> then = List.of(task(1, day(0), day(4)), task(2, day(7), day(11)));
        List<Entry> now = List.of(task(1, day(0), day(4)), task(2, day(9), day(15)));
        Result result = comparison.compare(then, now, day(11), day(11));
        // The project still says Friday. The plan runs two working days past it, to Tuesday.
        assertThat(result.projectEnd()).isEqualTo(new DateChange(day(11), day(11), 0, Change.UNCHANGED));
        assertThat(result.planEnd()).isEqualTo(new DateChange(day(11), day(15), 2, Change.SLIPPED));

        assertThat(comparison.compare(then, now, day(11), day(4)).projectEnd()).isEqualTo(new DateChange(day(11), day(4), -5, Change.PULLED_IN));
        assertThat(comparison.compare(then, now, null, day(11)).projectEnd()).isEqualTo(new DateChange(null, day(11), null, Change.ADDED));
        assertThat(comparison.compare(then, now, day(11), null).projectEnd()).isEqualTo(new DateChange(day(11), null, null, Change.REMOVED));
        assertThat(comparison.compare(List.of(), List.of(), null, null).planEnd()).isEqualTo(new DateChange(null, null, null, Change.UNCHANGED));
    }

    @Test
    void countsEachKindOfChangeAndAddsUpTheEstimatesOfTheTasksThatCarryThem() {
        Entry parent = new Entry(1, null, "Build", day(0), day(9), 40, false);
        Entry sub = new Entry(2, 1L, "Endpoints", day(0), day(4), 16, false);
        Entry launch = new Entry(3, null, "Launch", day(11), day(11), 0, true);
        Entry dropped = new Entry(4, null, "Dropped", day(0), day(1), 6, false);
        List<Entry> then = List.of(parent, sub, launch, dropped);
        List<Entry> now = List.of(parent, new Entry(2, 1L, "Endpoints", day(2), day(8), 24, false),
                new Entry(3, null, "Launch", day(10), day(10), 0, true), new Entry(5, null, "Extra", day(7), day(8), 10, false));
        Result result = comparison.compare(then, now, null, null);

        assertThat(result.counts()).containsEntry(Change.UNCHANGED, 1).containsEntry(Change.SLIPPED, 1)
                .containsEntry(Change.PULLED_IN, 1).containsEntry(Change.ADDED, 1).containsEntry(Change.REMOVED, 1);
        // The parent's forty hours are not counted next to its subtask's, and a milestone has none.
        assertThat(result.baselineEstimateHours()).isEqualTo(22);
        assertThat(result.currentEstimateHours()).isEqualTo(34);
        // In the order of the plan as it is now. A task that is gone stands where it stood.
        assertThat(result.tasks()).extracting(TaskChange::title).containsExactly("Build", "Dropped", "Endpoints", "Extra", "Launch");
    }

    @Test
    void phasesAreComparedByTheirStartAndEndAndAreNotCountedAsTasks() {
        // Discovery ends two working days later, from Friday to the Tuesday after. Build is gone, Launch is new.
        List<Entry> then = List.of(new Entry(1, null, "Discovery", day(0), day(4), 0, false),
                new Entry(2, null, "Build", day(7), day(11), 0, false));
        List<Entry> now = List.of(new Entry(1, null, "Discovery", day(0), day(8), 0, false),
                new Entry(3, null, "Launch", day(14), day(14), 0, false));
        List<TaskChange> phases = comparison.comparePhases(then, now);
        assertThat(phases).extracting(TaskChange::title).containsExactly("Discovery", "Build", "Launch");
        assertThat(phases).extracting(TaskChange::change).containsExactly(Change.SLIPPED, Change.REMOVED, Change.ADDED);
        assertThat(phases.getFirst().finishSlipDays()).isEqualTo(2);
        assertThat(phases.getFirst().startSlipDays()).isZero();
        assertThat(phases.get(1).existsNow()).isFalse();
    }
}
