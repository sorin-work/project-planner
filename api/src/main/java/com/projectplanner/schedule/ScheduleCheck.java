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
        /** The task starts before {@code relatedTaskId}, which it waits for, is due (plus the lag). */
        STARTS_TOO_EARLY,
        /** The task waits for {@code relatedTaskId}, which has no due date, so the link is not enforced. */
        WAITS_FOR_UNSCHEDULED,
        /** The task is due after the project's end date. */
        AFTER_PROJECT_END,
        /** The subtask has working days outside the dates of its parent, {@code relatedTaskId}. */
        OUTSIDE_PARENT
    }

    /**
     * @param relatedTaskId  the predecessor or the parent; null for {@link IssueType#AFTER_PROJECT_END}
     * @param days           working days the task starts too early, is due too late or lies outside
     *                       its parent; null for {@link IssueType#WAITS_FOR_UNSCHEDULED}
     * @param suggestedStart for {@link IssueType#STARTS_TOO_EARLY} only: the first day every
     *                       predecessor of the task allows
     * @param suggestedDue   the due date that goes with it, keeping the task's length in working days
     */
    public record Issue(IssueType type, long taskId, Long relatedTaskId, Integer days,
                        LocalDate suggestedStart, LocalDate suggestedDue) {
    }

    private final WorkCalendar calendar;
    private final Rescheduler rescheduler;

    public ScheduleCheck(WorkCalendar calendar) {
        this.calendar = calendar;
        this.rescheduler = new Rescheduler(calendar);
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
            if (task.isScheduled()) {
                checkLinks(task, graph, byId, done, issues);
            }
            Long parentId = parents.get(task.id());
            if (parentId != null && byId.containsKey(parentId)) {
                int outside = daysOutside(task, byId.get(parentId));
                if (outside > 0) {
                    issues.add(new Issue(IssueType.OUTSIDE_PARENT, task.id(), parentId, outside, null, null));
                }
            }
            if (projectEnd != null && task.due() != null) {
                int late = calendar.workingDaysBetween(projectEnd.plusDays(1), task.due());
                if (late > 0) {
                    issues.add(new Issue(IssueType.AFTER_PROJECT_END, task.id(), null, late, null, null));
                }
            }
        }
        issues.sort(Comparator.comparing((Issue i) -> firstDay(byId.get(i.taskId())))
                .thenComparing(Issue::taskId)
                .thenComparing(Issue::type)
                .thenComparing(i -> i.relatedTaskId() == null ? 0L : i.relatedTaskId()));
        return issues;
    }

    /** One issue per link that does not hold. Only tasks with both dates get here, as in the rescheduler. */
    private void checkLinks(ScheduledTask task, DependencyGraph graph, Map<Long, ScheduledTask> byId,
                            Set<Long> done, List<Issue> issues) {
        LocalDate earliest = rescheduler.earliestStart(graph, byId, task.id());
        for (Link link : graph.predecessors(task.id())) {
            ScheduledTask predecessor = byId.get(link.predecessorId());
            if (predecessor == null) {
                continue;
            }
            LocalDate allowed = rescheduler.earliestStart(link, predecessor);
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
                issues.add(new Issue(IssueType.STARTS_TOO_EARLY, task.id(), predecessor.id(), early, fix.start(), fix.due()));
            }
        }
    }

    /** Working days of the subtask that fall before its parent starts or after it is due. */
    private int daysOutside(ScheduledTask subtask, ScheduledTask parent) {
        LocalDate first = firstDay(subtask);
        LocalDate last = subtask.due() != null ? subtask.due() : subtask.start();
        if (first == null) {
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
