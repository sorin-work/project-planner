package com.projectplanner.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Looks for places where the plan contradicts itself. Nothing is changed: the
 * result is a list of issues to show next to the plan.
 *
 * <p>The dependency rule is the {@link Rescheduler}'s own. A link is reported
 * when the rescheduler would push the task if it looked at it, and the dates
 * suggested as the fix are the ones it would give the task. A start on a
 * non-working day counts as the next working day, as it does on the critical
 * path, so a task that starts on the Saturday before the Monday it may start
 * on is left alone.
 *
 * <p>Finished tasks are not reported. Their dates are history, and a finished
 * task without dates holds nobody up.
 */
public final class ScheduleCheck {

    public enum IssueType {
        /**
         * The task is earlier than its link to {@code relatedTaskId} allows: it starts before the
         * predecessor is due (finish-to-start) or starts (start-to-start), or is due before the
         * predecessor is due (finish-to-finish) or starts (start-to-finish), plus the lag.
         */
        STARTS_TOO_EARLY,
        /** The task waits for {@code relatedTaskId}, which has no due date, so the link is not enforced. */
        WAITS_FOR_UNSCHEDULED,
        /** The task is due after the project's end date. */
        AFTER_PROJECT_END,
        /** The subtask has working days outside the dates of its parent, {@code relatedTaskId}. */
        OUTSIDE_PARENT
    }

    /**
     * @param relatedTaskId    the predecessor or the parent; null for {@link IssueType#AFTER_PROJECT_END}
     * @param days             working days the task starts too early, is due too late or lies outside
     *                         its parent; null for {@link IssueType#WAITS_FOR_UNSCHEDULED}
     * @param suggestedStart   for {@link IssueType#STARTS_TOO_EARLY} only: the first day every
     *                         predecessor of the task allows
     * @param suggestedDue     the due date that goes with it, keeping the task's length in working days
     * @param suggestionCauses what moving the task to the suggested dates would newly get it reported
     *                         for: {@link IssueType#AFTER_PROJECT_END}, {@link IssueType#OUTSIDE_PARENT},
     *                         both or nothing. The suggestion only looks at the dependencies, so this
     *                         is the price to show next to it.
     */
    public record Issue(IssueType type, long taskId, Long relatedTaskId, Integer days,
                        LocalDate suggestedStart, LocalDate suggestedDue, List<IssueType> suggestionCauses) {

        public Issue(IssueType type, long taskId, Long relatedTaskId, Integer days, LocalDate suggestedStart, LocalDate suggestedDue) {
            this(type, taskId, relatedTaskId, days, suggestedStart, suggestedDue, List.of());
        }
    }

    private final WorkCalendar calendar;
    private final Rescheduler rescheduler;
    private final LinkRule rule;

    public ScheduleCheck(WorkCalendar calendar) {
        this.calendar = calendar;
        this.rescheduler = new Rescheduler(calendar);
        this.rule = new LinkRule(calendar);
    }

    /**
     * @param tasks      current dates of every task in the project
     * @param links      all dependencies in the project
     * @param parents    parent id by subtask id
     * @param done       ids of the finished tasks
     * @param projectEnd the project's end date, or null if it has none
     * @return the issues, ordered by the date of the task they are about
     */
    public List<Issue> check(Collection<ScheduledTask> tasks, Collection<Link> links, Map<Long, Long> parents,
                             Set<Long> done, LocalDate projectEnd) {
        Map<Long, ScheduledTask> byId = new HashMap<>();
        for (ScheduledTask task : tasks) {
            byId.put(task.id(), task);
        }
        DependencyGraph graph = new DependencyGraph(byId.keySet(), links);

        List<Issue> issues = new ArrayList<>();
        for (ScheduledTask task : tasks) {
            if (done.contains(task.id())) {
                continue;
            }
            Long parentId = parents.get(task.id());
            ScheduledTask parent = parentId == null ? null : byId.get(parentId);
            if (task.isScheduled()) {
                checkLinks(task, parent, projectEnd, graph, byId, done, issues);
            }
            int outside = daysOutside(task, parent);
            if (outside > 0) {
                issues.add(new Issue(IssueType.OUTSIDE_PARENT, task.id(), parentId, outside, null, null));
            }
            int late = daysLate(task, projectEnd);
            if (late > 0) {
                issues.add(new Issue(IssueType.AFTER_PROJECT_END, task.id(), null, late, null, null));
            }
        }
        issues.sort(Comparator.comparing((Issue i) -> firstDay(byId.get(i.taskId())))
                .thenComparing(Issue::taskId)
                .thenComparing(Issue::type)
                .thenComparing(i -> i.relatedTaskId() == null ? 0L : i.relatedTaskId()));
        return issues;
    }

    /** One issue per link that does not hold. Only tasks with both dates get here, as in the rescheduler. */
    private void checkLinks(ScheduledTask task, ScheduledTask parent, LocalDate projectEnd, DependencyGraph graph,
                            Map<Long, ScheduledTask> byId, Set<Long> done, List<Issue> issues) {
        LocalDate earliest = rescheduler.earliestStart(graph, byId, task.id());
        for (Link link : graph.predecessors(task.id())) {
            ScheduledTask predecessor = byId.get(link.predecessorId());
            if (predecessor == null) {
                continue;
            }
            LocalDate allowed = rule.earliestStart(link, predecessor, task);
            if (allowed == null) {
                if (!done.contains(predecessor.id())) {
                    issues.add(new Issue(IssueType.WAITS_FOR_UNSCHEDULED, task.id(), predecessor.id(), null, null, null));
                }
                continue;
            }
            int early = calendar.workingDaysBetween(task.start(), allowed.minusDays(1));
            if (early > 0) {
                // The fix has to satisfy every predecessor, not only this one.
                ScheduledTask fix = rescheduler.startingOn(task, earliest);
                issues.add(new Issue(IssueType.STARTS_TOO_EARLY, task.id(), predecessor.id(), early, fix.start(), fix.due(),
                        causedBy(task, fix, parent, projectEnd)));
            }
        }
    }

    /**
     * What {@code moved} would be reported for that {@code task} is not reported for today.
     * The same two rules as in {@link #check}, asked about the suggested dates.
     */
    private List<IssueType> causedBy(ScheduledTask task, ScheduledTask moved, ScheduledTask parent, LocalDate projectEnd) {
        List<IssueType> causes = new ArrayList<>();
        if (daysLate(moved, projectEnd) > 0 && daysLate(task, projectEnd) == 0) {
            causes.add(IssueType.AFTER_PROJECT_END);
        }
        if (daysOutside(moved, parent) > 0 && daysOutside(task, parent) == 0) {
            causes.add(IssueType.OUTSIDE_PARENT);
        }
        return List.copyOf(causes);
    }

    /** Working days from the day after the project's end up to the task's due date. */
    private int daysLate(ScheduledTask task, LocalDate projectEnd) {
        return projectEnd == null || task.due() == null ? 0 : calendar.workingDaysBetween(projectEnd.plusDays(1), task.due());
    }

    /** Working days of the subtask that fall before its parent starts or after it is due. Zero without a parent. */
    private int daysOutside(ScheduledTask subtask, ScheduledTask parent) {
        LocalDate first = firstDay(subtask);
        LocalDate last = subtask.due() != null ? subtask.due() : subtask.start();
        if (parent == null || first == null) {
            return 0;
        }
        int outside = 0;
        if (parent.start() != null && first.isBefore(parent.start())) {
            LocalDate dayBefore = parent.start().minusDays(1);
            outside += calendar.workingDaysBetween(first, last.isBefore(dayBefore) ? last : dayBefore);
        }
        if (parent.due() != null && last.isAfter(parent.due())) {
            LocalDate dayAfter = parent.due().plusDays(1);
            outside += calendar.workingDaysBetween(first.isAfter(dayAfter) ? first : dayAfter, last);
        }
        return outside;
    }

    private static LocalDate firstDay(ScheduledTask task) {
        return task.start() != null ? task.start() : task.due();
    }
}
