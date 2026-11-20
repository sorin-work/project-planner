package com.projectplanner.schedule;

import com.projectplanner.schedule.ScheduleCheck.Issue;
import com.projectplanner.schedule.ScheduleCheck.IssueType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ScheduleCheckTest {

    private final ScheduleCheck check = new ScheduleCheck(WorkCalendar.DEFAULT);
    private final Rescheduler rescheduler = new Rescheduler(WorkCalendar.DEFAULT);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    /** An issue about a finish-to-start link, which every link here is unless it says otherwise. */
    private static Issue fs(IssueType type, long taskId, Long relatedTaskId, Integer days, LocalDate start, LocalDate due) {
        return fs(type, taskId, relatedTaskId, days, start, due, List.of());
    }

    private static Issue fs(IssueType type, long taskId, Long relatedTaskId, Integer days, LocalDate start, LocalDate due,
                            List<IssueType> causes) {
        return new Issue(type, taskId, relatedTaskId, days, start, due, causes, DependencyType.FS);
    }

    private List<Issue> links(List<ScheduledTask> tasks, List<Link> links) {
        return check.check(tasks, links, Map.of(), Set.of(), null);
    }

    @Test
    void aPlanWhereEveryLinkHoldsHasNoIssues() {
        var design = new ScheduledTask(1, day(0), day(2));
        var build = new ScheduledTask(2, day(3), day(4));
        var launch = new ScheduledTask(3, day(7), day(7));
        assertThat(links(List.of(design, build, launch), List.of(new Link(1, 2), new Link(2, 3)))).isEmpty();
    }

    @Test
    void reportsATaskThatStartsBeforeItsPredecessorIsDue() {
        var design = new ScheduledTask(1, day(0), day(4)); // Mon - Fri
        var build = new ScheduledTask(2, day(2), day(4)); // Wed - Fri, three days
        List<Issue> issues = links(List.of(design, build), List.of(new Link(1, 2)));
        // Build may start on Monday. Wed, Thu and Fri are too early, and three working days from Monday end on Wednesday.
        assertThat(issues).containsExactly(fs(IssueType.STARTS_TOO_EARLY, 2, 1L, 3, day(7), day(9)));
    }

    @Test
    void countsTheLagInWorkingDays() {
        var design = new ScheduledTask(1, day(0), day(3)); // ends Thursday
        var build = new ScheduledTask(2, day(4), day(4)); // Friday, right after
        assertThat(links(List.of(design, build), List.of(new Link(1, 2)))).isEmpty();
        // With two working days of lag the first allowed day is Tuesday.
        assertThat(links(List.of(design, build), List.of(new Link(1, 2, 2))))
                .containsExactly(fs(IssueType.STARTS_TOO_EARLY, 2, 1L, 2, day(8), day(8)));
    }

    @Test
    void aStartOnTheWeekendBeforeTheAllowedMondayIsNotTooEarly() {
        var design = new ScheduledTask(1, day(0), day(4)); // due Friday
        var build = new ScheduledTask(2, day(5), day(8)); // Saturday - Tuesday
        assertThat(links(List.of(design, build), List.of(new Link(1, 2)))).isEmpty();
    }

    @Test
    void oneIssuePerBrokenLinkAndOneFixForTheTask() {
        var copy = new ScheduledTask(1, day(0), day(1)); // due Tuesday
        var design = new ScheduledTask(2, day(0), day(3)); // due Thursday
        var review = new ScheduledTask(3, day(0), day(0)); // due Monday
        var build = new ScheduledTask(4, day(1), day(2)); // Tue - Wed
        List<Issue> issues = links(List.of(copy, design, review, build),
                List.of(new Link(1, 4), new Link(2, 4), new Link(3, 4)));
        // The link to the review holds. Each of the other two says how early Build is for it,
        // and both carry the dates that satisfy the later one.
        assertThat(issues).containsExactly(
                fs(IssueType.STARTS_TOO_EARLY, 4, 1L, 1, day(4), day(7)),
                fs(IssueType.STARTS_TOO_EARLY, 4, 2L, 3, day(4), day(7)));
    }

    @Test
    void theFixIsWhatTheReschedulerWouldDoAndLeavesNothingToReport() {
        var design = new ScheduledTask(1, day(0), day(8));
        var build = new ScheduledTask(2, day(3), day(9)); // Thu - next Wed, five working days
        var test = new ScheduledTask(3, day(10), day(11));
        List<Link> links = List.of(new Link(1, 2, 1), new Link(2, 3));

        Issue issue = links(List.of(design, build, test), links).getFirst();
        ScheduledTask pushed = rescheduler.propagate(List.of(design, build, test), links, Set.of(1L)).get(2L);
        assertThat(issue.suggestedStart()).isEqualTo(pushed.start());
        assertThat(issue.suggestedDue()).isEqualTo(pushed.due());

        var moved = new ScheduledTask(2, issue.suggestedStart(), issue.suggestedDue());
        // Build is fine now. Test is the next one in the chain to start too early,
        // which the API takes care of when Build moves.
        assertThat(links(List.of(design, moved, test), links))
                .extracting(Issue::taskId).containsExactly(3L);
    }

    @Test
    void saysWhenTheFixWouldPutTheTaskAfterTheProjectEnd() {
        var design = new ScheduledTask(1, day(7), day(11)); // due Friday 13 March, the project's last day
        var build = new ScheduledTask(2, day(9), day(11)); // Wed - Fri, waits for Design
        List<Issue> issues = check.check(List.of(design, build), List.of(new Link(1, 2)), Map.of(), Set.of(), day(11));
        // Moving Build to Monday - Wednesday clears the link and makes it three days late instead.
        assertThat(issues).containsExactly(fs(IssueType.STARTS_TOO_EARLY, 2, 1L, 3, day(14), day(16),
                List.of(IssueType.AFTER_PROJECT_END)));
    }

    @Test
    void saysWhenTheFixWouldTakeASubtaskOutOfItsParent() {
        var design = new ScheduledTask(1, day(0), day(3)); // due Thursday
        var build = new ScheduledTask(2, day(0), day(4)); // the parent, Mon - Fri
        var endpoints = new ScheduledTask(3, day(2), day(3)); // Wed - Thu, inside Build, waits for Design
        List<Issue> issues = check.check(List.of(design, build, endpoints), List.of(new Link(1, 3)),
                Map.of(3L, 2L), Set.of(), day(4));
        // Fri - Mon: Monday is past both the parent and the project.
        assertThat(issues).containsExactly(fs(IssueType.STARTS_TOO_EARLY, 3, 1L, 2, day(4), day(7),
                List.of(IssueType.AFTER_PROJECT_END, IssueType.OUTSIDE_PARENT)));
    }

    @Test
    void theFixCausesNothingWhenItFitsOrWhenTheTaskIsReportedForItAlready() {
        var design = new ScheduledTask(1, day(0), day(3));
        var parent = new ScheduledTask(2, day(0), day(11));
        var inside = new ScheduledTask(3, day(2), day(3)); // moves to Fri - Mon, still inside and in time
        var late = new ScheduledTask(4, day(2), day(15)); // already past the end and past its parent
        List<Issue> issues = check.check(List.of(design, parent, inside, late), List.of(new Link(1, 3), new Link(1, 4)),
                Map.of(3L, 2L, 4L, 2L), Set.of(), day(11));
        assertThat(issues).filteredOn(i -> i.type() == IssueType.STARTS_TOO_EARLY)
                .extracting(Issue::taskId, Issue::suggestionCauses)
                .containsExactly(tuple(3L, List.of()), tuple(4L, List.of()));
        assertThat(issues).extracting(Issue::type).contains(IssueType.AFTER_PROJECT_END, IssueType.OUTSIDE_PARENT);
    }

    @Test
    void aMilestoneIsMovedAsASingleDay() {
        var build = new ScheduledTask(1, day(0), day(4));
        var launch = new ScheduledTask(2, day(3), day(3));
        assertThat(links(List.of(build, launch), List.of(new Link(1, 2))))
                .containsExactly(fs(IssueType.STARTS_TOO_EARLY, 2, 1L, 2, day(7), day(7)));
    }

    @Test
    void reportsALinkToATaskWithoutADueDate() {
        var design = new ScheduledTask(1, null, null);
        var copy = new ScheduledTask(2, day(0), null);
        var build = new ScheduledTask(3, day(2), day(4));
        assertThat(links(List.of(design, copy, build), List.of(new Link(1, 3), new Link(2, 3)))).containsExactly(
                fs(IssueType.WAITS_FOR_UNSCHEDULED, 3, 1L, null, null, null),
                fs(IssueType.WAITS_FOR_UNSCHEDULED, 3, 2L, null, null, null));
    }

    @Test
    void aTaskWithoutDatesOfItsOwnIsNotCheckedAgainstItsLinks() {
        var design = new ScheduledTask(1, day(0), day(4));
        var undated = new ScheduledTask(2, null, null);
        var dueOnly = new ScheduledTask(3, null, day(1));
        var nothing = new ScheduledTask(4, null, null);
        assertThat(links(List.of(design, undated, dueOnly, nothing),
                List.of(new Link(1, 2), new Link(1, 3), new Link(4, 2)))).isEmpty();
    }

    @Test
    void finishedTasksAreLeftAlone() {
        var research = new ScheduledTask(1, null, null); // finished, never had dates
        var design = new ScheduledTask(2, day(0), day(4));
        var build = new ScheduledTask(3, day(2), day(9)); // finished, started early and ran past the end
        var test = new ScheduledTask(4, day(10), day(11));
        List<Issue> issues = check.check(List.of(research, design, build, test),
                List.of(new Link(1, 4), new Link(2, 3), new Link(3, 4)), Map.of(), Set.of(1L, 3L), day(4));
        assertThat(issues).containsExactly(new Issue(IssueType.AFTER_PROJECT_END, 4, null, 5, null, null));
    }

    @Test
    void aFinishedPredecessorStillCountsWithItsDueDate() {
        var design = new ScheduledTask(1, day(0), day(4));
        var build = new ScheduledTask(2, day(3), day(4));
        List<Issue> issues = check.check(List.of(design, build), List.of(new Link(1, 2)), Map.of(), Set.of(1L), null);
        assertThat(issues).extracting(Issue::type).containsExactly(IssueType.STARTS_TOO_EARLY);
    }

    @Test
    void reportsTasksDueAfterTheProjectEndInWorkingDays() {
        var onTime = new ScheduledTask(1, day(0), day(4)); // due on the last day, a Friday
        var weekend = new ScheduledTask(2, day(3), day(5)); // due on the Saturday after: no working day late
        var late = new ScheduledTask(3, day(3), day(8)); // due Tuesday
        var dueOnly = new ScheduledTask(4, null, day(7));
        var startOnly = new ScheduledTask(5, day(9), null);
        List<ScheduledTask> tasks = List.of(onTime, weekend, late, dueOnly, startOnly);

        assertThat(check.check(tasks, List.of(), Map.of(), Set.of(), day(4))).containsExactly(
                new Issue(IssueType.AFTER_PROJECT_END, 3, null, 2, null, null),
                new Issue(IssueType.AFTER_PROJECT_END, 4, null, 1, null, null));
        assertThat(check.check(tasks, List.of(), Map.of(), Set.of(), null)).isEmpty();
    }

    @Test
    void reportsSubtasksOutsideTheirParent() {
        var parent = new ScheduledTask(1, day(2), day(8)); // Wed - next Tue
        var inside = new ScheduledTask(2, day(3), day(7));
        var early = new ScheduledTask(3, day(0), day(3)); // Mon and Tue are before the parent starts
        var late = new ScheduledTask(4, day(8), day(10)); // Wed and Thu are after it is due
        var both = new ScheduledTask(5, day(1), day(9)); // one day on each side
        var apart = new ScheduledTask(6, day(14), day(15)); // entirely after
        var dueOnly = new ScheduledTask(7, null, day(9));
        var undated = new ScheduledTask(8, null, null);
        Map<Long, Long> parents = Map.of(2L, 1L, 3L, 1L, 4L, 1L, 5L, 1L, 6L, 1L, 7L, 1L, 8L, 1L);

        List<Issue> issues = check.check(List.of(parent, inside, early, late, both, apart, dueOnly, undated),
                List.of(), parents, Set.of(), null);
        assertThat(issues).containsExactly(
                new Issue(IssueType.OUTSIDE_PARENT, 3, 1L, 2, null, null),
                new Issue(IssueType.OUTSIDE_PARENT, 5, 1L, 2, null, null),
                new Issue(IssueType.OUTSIDE_PARENT, 4, 1L, 2, null, null),
                new Issue(IssueType.OUTSIDE_PARENT, 7, 1L, 1, null, null),
                new Issue(IssueType.OUTSIDE_PARENT, 6, 1L, 2, null, null));
    }

    @Test
    void aSubtaskThatOnlyRunsIntoTheWeekendIsInsideItsParent() {
        var parent = new ScheduledTask(1, day(0), day(4)); // Mon - Fri
        var subtask = new ScheduledTask(2, day(3), day(5)); // Thu - Sat
        assertThat(check.check(List.of(parent, subtask), List.of(), Map.of(2L, 1L), Set.of(), null)).isEmpty();
    }

    @Test
    void aSubtaskIsNotMeasuredAgainstDatesItsParentDoesNotHave() {
        var noDates = new ScheduledTask(1, null, null);
        var dueOnly = new ScheduledTask(2, null, day(4));
        var first = new ScheduledTask(3, day(0), day(9));
        var second = new ScheduledTask(4, day(0), day(9));
        List<Issue> issues = check.check(List.of(noDates, dueOnly, first, second), List.of(),
                Map.of(3L, 1L, 4L, 2L), Set.of(), null);
        // Nothing to say about the first. The second runs three working days past the only date its parent has.
        assertThat(issues).containsExactly(new Issue(IssueType.OUTSIDE_PARENT, 4, 2L, 3, null, null));
    }

    @Test
    void ordersIssuesByTheDateOfTheTask() {
        var design = new ScheduledTask(1, day(0), day(4));
        var later = new ScheduledTask(2, day(3), day(9));
        var sooner = new ScheduledTask(3, day(1), day(9));
        List<Issue> issues = check.check(List.of(design, later, sooner), List.of(new Link(1, 2), new Link(1, 3)),
                Map.of(), Set.of(), day(4));
        assertThat(issues).extracting(Issue::taskId, Issue::type).containsExactly(
                tuple(3L, IssueType.STARTS_TOO_EARLY),
                tuple(3L, IssueType.AFTER_PROJECT_END),
                tuple(2L, IssueType.STARTS_TOO_EARLY),
                tuple(2L, IssueType.AFTER_PROJECT_END));
    }

    @Test
    void aStartToStartLinkThatHoldsIsNotReportedThoughTheTasksOverlap() {
        var design = new ScheduledTask(1, day(0), day(4));
        var build = new ScheduledTask(2, day(1), day(3));
        assertThat(links(List.of(design, build), List.of(new Link(1, 2, 1, DependencyType.SS)))).isEmpty();
        // Design is due on Friday; four days of lead put the earliest start on Tuesday, where Build starts.
        assertThat(links(List.of(design, build), List.of(new Link(1, 2, -4)))).isEmpty();
        assertThat(links(List.of(design, build), List.of(new Link(1, 2, -3)))).extracting(Issue::days).containsExactly(1);
    }

    @Test
    void eachTypeIsReportedByItsOwnRuleWithTheDatesTheReschedulerWouldGive() {
        var design = new ScheduledTask(1, day(2), day(8));
        var build = new ScheduledTask(2, day(0), day(1));
        for (DependencyType type : DependencyType.values()) {
            Link link = new Link(1, 2, 0, type);
            List<Issue> issues = links(List.of(design, build), List.of(link));
            assertThat(issues).as(type.name()).hasSize(1);
            Issue issue = issues.getFirst();
            assertThat(issue.linkType()).isEqualTo(type);
            ScheduledTask fix = rescheduler.propagate(List.of(design, build), List.of(link), Set.of(1L)).get(2L);
            assertThat(issue.suggestedStart()).as(type.name()).isEqualTo(fix.start());
            assertThat(issue.suggestedDue()).as(type.name()).isEqualTo(fix.due());
            // After the move the link holds.
            assertThat(links(List.of(design, fix), List.of(link))).as(type.name()).isEmpty();
        }
    }
}
