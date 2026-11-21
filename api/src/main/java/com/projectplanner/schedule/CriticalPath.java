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
 * Critical path method over the scheduled tasks of a project.
 *
 * <p>Days are counted as working-day offsets from the earliest task start. The
 * forward pass treats each task's planned start as "start no earlier than", so
 * float is measured against the plan rather than an idealised schedule. Tasks
 * with zero total float are critical: any delay to them delays the project end.
 *
 * <p>A finished task is no longer a plan. It stays at its own dates in both passes:
 * its links do not move it, and it does not hold back the tasks it waited for. It
 * still counts towards the end and still holds back the tasks that wait for it, the
 * way the rescheduler pushes an open successor. Its float means nothing, so it has
 * none and is never critical. Only the critical path makes this exception;
 * {@link LinkRule} itself, the rescheduler and the schedule check are unchanged.
 */
public final class CriticalPath {

    private final WorkCalendar calendar;
    private final LinkRule rule;

    public CriticalPath(WorkCalendar calendar) {
        this.calendar = calendar;
        this.rule = new LinkRule(calendar);
    }

    public Result calculate(Collection<ScheduledTask> tasks, Collection<Link> links) {
        return calculate(tasks, links, Set.of());
    }

    /** @param done the ids of the finished tasks */
    public Result calculate(Collection<ScheduledTask> tasks, Collection<Link> links, Set<Long> done) {
        // Read the way the rescheduler reads them: a task on days off only is one day on the next working day.
        List<ScheduledTask> scheduled = tasks.stream().filter(ScheduledTask::isScheduled).map(rule::normalise).toList();
        if (scheduled.isEmpty()) {
            return new Result(null, null, 0, List.of(), List.of());
        }
        Map<Long, ScheduledTask> byId = new HashMap<>();
        scheduled.forEach(t -> byId.put(t.id(), t));
        // A link into a finished task asks nothing any more, in either pass.
        List<Link> relevant = links.stream()
                .filter(l -> byId.containsKey(l.predecessorId()) && byId.containsKey(l.successorId()))
                .filter(l -> !done.contains(l.successorId()))
                .toList();
        DependencyGraph graph = new DependencyGraph(byId.keySet(), relevant);
        List<Long> order = graph.topologicalOrder();

        LocalDate origin = calendar.onOrAfter(scheduled.stream().map(ScheduledTask::start).min(Comparator.naturalOrder()).orElseThrow());
        Map<Long, Integer> duration = new HashMap<>();
        Map<Long, Integer> es = new HashMap<>();
        Map<Long, Integer> ef = new HashMap<>();

        for (long id : order) {
            ScheduledTask task = byId.get(id);
            int d = calendar.duration(task.start(), task.due());
            int start = offset(origin, task.start());
            for (Link link : graph.predecessors(id)) {
                start = Math.max(start, LinkRule.earliestStartOffset(link, es.get(link.predecessorId()), ef.get(link.predecessorId()), d));
            }
            duration.put(id, d);
            es.put(id, start);
            ef.put(id, start + d - 1);
        }

        int finish = ef.values().stream().max(Integer::compare).orElse(0);

        Map<Long, Integer> ls = new HashMap<>();
        Map<Long, Integer> lf = new HashMap<>();
        for (long id : order.reversed()) {
            int latestFinish = finish;
            for (Link link : graph.successors(id)) {
                latestFinish = Math.min(latestFinish,
                        LinkRule.latestFinishOffset(link, ls.get(link.successorId()), lf.get(link.successorId()), duration.get(id)));
            }
            if (done.contains(id)) {
                // Where it was done, whatever comes after it.
                lf.put(id, ef.get(id));
                ls.put(id, es.get(id));
            } else {
                lf.put(id, latestFinish);
                ls.put(id, latestFinish - duration.get(id) + 1);
            }
        }

        List<TaskTiming> timings = new ArrayList<>();
        for (long id : order) {
            Integer slack = done.contains(id) ? null : ls.get(id) - es.get(id);
            timings.add(new TaskTiming(id,
                    calendar.addWorkingDays(origin, es.get(id)),
                    calendar.addWorkingDays(origin, ef.get(id)),
                    calendar.addWorkingDays(origin, ls.get(id)),
                    calendar.addWorkingDays(origin, lf.get(id)),
                    slack, slack != null && slack <= 0));
        }
        timings.sort(Comparator.comparing(TaskTiming::earlyStart).thenComparing(TaskTiming::taskId));
        List<Long> critical = timings.stream().filter(TaskTiming::critical).map(TaskTiming::taskId).toList();
        return new Result(origin, calendar.addWorkingDays(origin, finish), finish + 1, critical, timings);
    }

    private int offset(LocalDate origin, LocalDate date) {
        LocalDate start = calendar.onOrAfter(date);
        return calendar.workingDaysBetween(origin, start) - 1;
    }

    /** @param floatDays null for a finished task, which has no float */
    public record TaskTiming(long taskId, LocalDate earlyStart, LocalDate earlyFinish,
                             LocalDate lateStart, LocalDate lateFinish, Integer floatDays, boolean critical) {
    }

    /**
     * @param durationDays project length in working days
     * @param criticalTaskIds critical tasks ordered by early start
     */
    public record Result(LocalDate start, LocalDate finish, int durationDays,
                         List<Long> criticalTaskIds, List<TaskTiming> tasks) {
    }
}
