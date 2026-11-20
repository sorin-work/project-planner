package com.projectplanner.schedule;

import com.projectplanner.schedule.ScheduleCheck.Issue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanShiftTest {

    private static final WorkCalendar CAL = WorkCalendar.DEFAULT;
    private final ScheduleCheck check = new ScheduleCheck(CAL);

    // 2026-03-02 is a Monday.
    private static LocalDate day(int offset) {
        return LocalDate.of(2026, 3, 2).plusDays(offset);
    }

    @Test
    void movesAPlanToItsNewStartAndKeepsLengthsAndGaps() {
        var shift = new PlanShift(CAL, day(0), day(100)); // Monday 2 March to Wednesday 10 June
        var design = new ScheduledTask(1, day(0), day(4)); // Mon - Fri
        var build = new ScheduledTask(2, day(7), day(9)); // the Monday after, three days
        var launch = new ScheduledTask(3, day(11), day(11)); // one free day after Build, then Friday

        assertThat(shift.task(design)).isEqualTo(new ScheduledTask(1, day(100), day(106))); // Wed - Tue
        assertThat(shift.task(build)).isEqualTo(new ScheduledTask(2, day(107), day(109))); // Wed - Fri
        assertThat(shift.task(launch)).isEqualTo(new ScheduledTask(3, day(113), day(113))); // Tuesday
        for (ScheduledTask task : List.of(design, build, launch)) {
            ScheduledTask moved = shift.task(task);
            assertThat(CAL.duration(moved.start(), moved.due())).isEqualTo(CAL.duration(task.start(), task.due()));
        }
        // Design to Build: the next working day. Build to Launch: one working day in between.
        assertThat(CAL.workingDaysBetween(shift.task(design).due(), shift.task(build).start())).isEqualTo(2);
        assertThat(CAL.workingDaysBetween(shift.task(build).due(), shift.task(launch).start())).isEqualTo(3);
    }

    @Test
    void movesEarlierAsWellAsLater() {
        var shift = new PlanShift(CAL, day(7), day(-7));
        assertThat(shift.task(new ScheduledTask(1, day(7), day(11)))).isEqualTo(new ScheduledTask(1, day(-7), day(-3)));
        // A date before the first day of the plan keeps its distance on the other side.
        assertThat(shift.end(day(4))).isEqualTo(day(-10));
        assertThat(shift.start(day(2))).isEqualTo(day(-12));
    }

    @Test
    void aStartOnADayOffBeginsOnTheNextWorkingDay() {
        var shift = new PlanShift(CAL, day(0), day(5)); // asked for a Saturday
        assertThat(shift.start(day(0))).isEqualTo(day(7));
        assertThat(shift.task(new ScheduledTask(1, day(0), day(1)))).isEqualTo(new ScheduledTask(1, day(7), day(8)));
    }

    @Test
    void tasksKeepTheDatesTheyHaveAndNoOthers() {
        var shift = new PlanShift(CAL, day(0), day(14));
        assertThat(shift.task(new ScheduledTask(1, null, null))).isEqualTo(new ScheduledTask(1, null, null));
        assertThat(shift.task(new ScheduledTask(2, day(3), null))).isEqualTo(new ScheduledTask(2, day(17), null));
        assertThat(shift.task(new ScheduledTask(3, null, day(4)))).isEqualTo(new ScheduledTask(3, null, day(18)));
        assertThat(shift.start(null)).isNull();
        assertThat(shift.end(null)).isNull();
    }

    @Test
    void readsADateOnTheWeekendAsTheWorkingDayItStandsFor() {
        var shift = new PlanShift(CAL, day(0), day(1)); // everything one working day later
        // Sat - Tue is Monday and Tuesday. Thu - Sat is Thursday and Friday.
        assertThat(shift.task(new ScheduledTask(1, day(5), day(8)))).isEqualTo(new ScheduledTask(1, day(8), day(9)));
        assertThat(shift.task(new ScheduledTask(2, day(3), day(5)))).isEqualTo(new ScheduledTask(2, day(4), day(7)));
        // A project that ends on a Saturday has Friday as its last working day.
        assertThat(shift.end(day(5))).isEqualTo(day(7));
        assertThat(shift.start(day(5))).isEqualTo(day(8));
    }

    @Test
    void findsTheFirstWorkingDayOfAPlan() {
        var tasks = List.of(new ScheduledTask(1, day(8), day(9)), new ScheduledTask(2, null, day(5)), new ScheduledTask(3, null, null));
        assertThat(PlanShift.firstDay(CAL, tasks, null)).isEqualTo(day(4)); // due on Saturday: Friday
        assertThat(PlanShift.firstDay(CAL, tasks, day(-1))).isEqualTo(day(0)); // the project starts on a Sunday: Monday
        assertThat(PlanShift.firstDay(CAL, tasks, day(20))).isEqualTo(day(4));
        assertThat(PlanShift.firstDay(CAL, List.of(new ScheduledTask(3, null, null)), null)).isNull();
    }

    @Test
    void theScheduleCheckSaysTheSameAboutTheMovedPlan() {
        // One of each: a link that holds, one that does not, a link to a task without dates,
        // a task past the project's end and a subtask that sticks out of its parent.
        var design = new ScheduledTask(1, day(0), day(4));
        var build = new ScheduledTask(2, day(3), day(11)); // starts two days before Design is due
        var test = new ScheduledTask(3, day(15), day(17)); // a day of lag after Build, as it should
        var research = new ScheduledTask(4, null, null);
        var endpoints = new ScheduledTask(5, day(2), day(8)); // subtask of Build, starts a day before it
        var launch = new ScheduledTask(6, day(21), day(21)); // two working days after the project ends
        List<ScheduledTask> tasks = List.of(design, build, test, research, endpoints, launch);
        List<Link> links = List.of(new Link(1, 2), new Link(2, 3, 1), new Link(4, 3), new Link(3, 6));
        Map<Long, Long> parents = Map.of(5L, 2L);
        LocalDate projectEnd = day(17);

        List<Issue> before = check.check(tasks, links, parents, Set.of(), projectEnd);
        assertThat(before).extracting(Issue::type).containsExactlyInAnyOrder(
                ScheduleCheck.IssueType.STARTS_TOO_EARLY, ScheduleCheck.IssueType.WAITS_FOR_UNSCHEDULED,
                ScheduleCheck.IssueType.OUTSIDE_PARENT, ScheduleCheck.IssueType.AFTER_PROJECT_END);

        for (int start : new int[] {1, 2, 3, 4, 5, 6, 7, 30, 100, -45}) {
            var shift = new PlanShift(CAL, PlanShift.firstDay(CAL, tasks, day(0)), day(start));
            List<ScheduledTask> moved = tasks.stream().map(shift::task).toList();
            List<Issue> after = check.check(moved, links, parents, Set.of(), shift.end(projectEnd));
            assertThat(summary(after)).as("start %s", day(start)).isEqualTo(summary(before));
            // The fix that is offered moves with the plan.
            Issue fixBefore = before.stream().filter(i -> i.suggestedStart() != null).findFirst().orElseThrow();
            Issue fixAfter = after.stream().filter(i -> i.suggestedStart() != null).findFirst().orElseThrow();
            assertThat(fixAfter.suggestedStart()).isEqualTo(shift.start(fixBefore.suggestedStart()));
            assertThat(fixAfter.suggestedDue()).isEqualTo(shift.end(fixBefore.suggestedDue()));
        }
    }

    @Test
    void randomPlansKeepExactlyTheirIssuesWhereverTheyAreMoved() {
        Random random = new Random(20261120L);
        int withIssues = 0;
        for (int round = 0; round < 3000; round++) {
            Plan plan = Plan.random(random);
            List<Issue> before = check.check(plan.tasks, plan.links, plan.parents, Set.of(), plan.end);
            withIssues += before.isEmpty() ? 0 : 1;

            LocalDate first = PlanShift.firstDay(CAL, plan.tasks, plan.start);
            if (first == null) {
                continue;
            }
            LocalDate newStart = day(random.nextInt(400) - 200);
            var shift = new PlanShift(CAL, first, newStart);
            List<ScheduledTask> moved = plan.tasks.stream().map(shift::task).toList();
            List<Issue> after = check.check(moved, plan.links, plan.parents, Set.of(), shift.end(plan.end));

            assertThat(summary(after)).as("plan %s moved to %s", plan.tasks, newStart).isEqualTo(summary(before));
            assertThat(PlanShift.firstDay(CAL, moved, shift.start(plan.start))).isEqualTo(CAL.onOrAfter(newStart));
            for (ScheduledTask task : moved) {
                assertThat(task.start() == null || CAL.isWorkingDay(task.start())).isTrue();
                assertThat(task.due() == null || CAL.isWorkingDay(task.due())).isTrue();
            }
        }
        // The plans are not trivially clean: most of them have something to report.
        assertThat(withIssues).isGreaterThan(1500);
    }

    @Test
    void aTaskWithoutAWorkingDayIsTheOneThingThatCannotBeKept() {
        // Design ends on Friday, the stand-in "task" lies on the weekend, Build starts on Monday.
        // Nothing is wrong: the weekend task has no working day and is in nobody's way.
        var design = new ScheduledTask(1, day(0), day(4));
        var weekend = new ScheduledTask(2, day(5), day(6));
        var build = new ScheduledTask(3, day(7), day(8));
        List<ScheduledTask> tasks = List.of(design, weekend, build);
        List<Link> links = List.of(new Link(1, 2), new Link(2, 3));
        assertThat(check.check(tasks, links, Map.of(), Set.of(), null)).isEmpty();

        // Moved by two days there is no weekend to put it on. It becomes a one-day task on the day
        // Build starts, and Build now starts a day too early for it.
        var shift = new PlanShift(CAL, day(0), day(2));
        List<ScheduledTask> moved = tasks.stream().map(shift::task).toList();
        assertThat(moved.get(1)).isEqualTo(new ScheduledTask(2, day(9), day(9)));
        assertThat(moved.get(2).start()).isEqualTo(day(9));
        assertThat(summary(check.check(moved, links, Map.of(), Set.of(), null))).containsExactly("STARTS_TOO_EARLY 3 2 1");
    }

    /** What the check found, without the dates: they are the one thing that is supposed to change. */
    private static Set<String> summary(List<Issue> issues) {
        Set<String> lines = new HashSet<>();
        for (Issue issue : issues) {
            lines.add(issue.type() + " " + issue.taskId() + " " + issue.relatedTaskId() + " " + issue.days());
        }
        return lines;
    }

    /** A random plan in which every task with dates has at least one working day. Weekend starts and ends are in. */
    private record Plan(List<ScheduledTask> tasks, List<Link> links, Map<Long, Long> parents, LocalDate start, LocalDate end) {

        static Plan random(Random random) {
            int size = 2 + random.nextInt(9);
            List<ScheduledTask> tasks = new ArrayList<>();
            for (long id = 1; id <= size; id++) {
                int kind = random.nextInt(10);
                LocalDate start = day(random.nextInt(40));
                LocalDate due = start.plusDays(random.nextInt(12));
                if (kind == 0) {
                    tasks.add(new ScheduledTask(id, null, null));
                } else if (kind == 1) {
                    tasks.add(new ScheduledTask(id, CAL.onOrAfter(start), null));
                } else if (kind == 2) {
                    tasks.add(new ScheduledTask(id, null, CAL.onOrBefore(due)));
                } else if (CAL.workingDaysBetween(start, due) == 0) {
                    tasks.add(new ScheduledTask(id, start, due.plusDays(2))); // out of the weekend
                } else {
                    tasks.add(new ScheduledTask(id, start, due));
                }
            }
            // Links only from a lower id to a higher one, so there is never a loop.
            List<Link> links = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = random.nextInt(size * 2); i > 0; i--) {
                long a = 1 + random.nextInt(size);
                long b = 1 + random.nextInt(size);
                if (a != b && seen.add(Math.min(a, b) + ">" + Math.max(a, b))) {
                    links.add(new Link(Math.min(a, b), Math.max(a, b), random.nextInt(4) == 0 ? random.nextInt(4) : 0));
                }
            }
            // The first tasks are parents, some of the others their subtasks.
            Map<Long, Long> parents = new HashMap<>();
            for (long id = 3; id <= size; id++) {
                if (random.nextInt(3) == 0) {
                    parents.put(id, 1L + random.nextInt(2));
                }
            }
            LocalDate start = random.nextInt(3) == 0 ? null : day(random.nextInt(10) - 3);
            LocalDate end = random.nextInt(3) == 0 ? null : day(20 + random.nextInt(30));
            return new Plan(tasks, links, parents, start, end);
        }
    }
}
