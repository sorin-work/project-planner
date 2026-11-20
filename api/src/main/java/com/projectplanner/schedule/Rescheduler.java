package com.projectplanner.schedule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pushes dependants later when a task moves so that every link still holds, by
 * the {@link LinkRule}. Tasks are only ever moved forward and keep their duration in
 * working days; dependants that already start late enough are left alone.
 */
public final class Rescheduler {

    private final WorkCalendar calendar;
    private final LinkRule rule;

    public Rescheduler(WorkCalendar calendar) {
        this.calendar = calendar;
        this.rule = new LinkRule(calendar);
    }

    /**
     * @param tasks   current dates of every task in the project (with the moved task's new dates)
     * @param links   all dependencies in the project
     * @param changed ids of the tasks that were just moved
     * @return the tasks that had to move, with their new dates, in the order they were moved
     */
    public Map<Long, ScheduledTask> propagate(Collection<ScheduledTask> tasks, Collection<Link> links, Set<Long> changed) {
        Map<Long, ScheduledTask> current = new HashMap<>();
        for (ScheduledTask task : tasks) {
            current.put(task.id(), task);
        }
        DependencyGraph graph = new DependencyGraph(current.keySet(), links);

        Set<Long> affected = new HashSet<>();
        for (long id : changed) {
            affected.addAll(graph.reachable(id));
        }

        Map<Long, ScheduledTask> moved = new LinkedHashMap<>();
        for (long id : graph.topologicalOrder()) {
            if (!affected.contains(id)) {
                continue;
            }
            ScheduledTask task = current.get(id);
            if (task == null || !task.isScheduled()) {
                continue;
            }
            LocalDate earliest = earliestStart(graph, current, id);
            if (earliest == null || !task.start().isBefore(earliest)) {
                continue;
            }
            ScheduledTask shifted = startingOn(task, earliest);
            current.put(id, shifted);
            moved.put(id, shifted);
        }
        return moved;
    }

    /**
     * {@code task} moved by a number of working days, later or (when negative) earlier.
     * The start moves that many working days and the due date keeps the length, so a
     * Thursday to Friday task moved one day later runs Friday to Monday. The result is
     * not pulled onto a working day afterwards: a Monday start moved one day earlier is
     * the Friday before. A task with only one date moves that date, and zero changes nothing.
     */
    public ScheduledTask shift(ScheduledTask task, int workingDays) {
        if (workingDays == 0) {
            return task;
        }
        if (task.isScheduled()) {
            return startingOn(task, calendar.addWorkingDays(task.start(), workingDays));
        }
        return new ScheduledTask(task.id(),
                task.start() == null ? null : calendar.addWorkingDays(task.start(), workingDays),
                task.due() == null ? null : calendar.addWorkingDays(task.due(), workingDays));
    }

    /** The first day {@code id} may start given all of its predecessors, or null if none constrain it. */
    LocalDate earliestStart(DependencyGraph graph, Map<Long, ScheduledTask> tasks, long id) {
        LocalDate earliest = null;
        for (Link link : graph.predecessors(id)) {
            LocalDate candidate = rule.earliestStart(link, tasks.get(link.predecessorId()), tasks.get(id));
            if (candidate != null && (earliest == null || candidate.isAfter(earliest))) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    /** {@code task} moved so that it starts on {@code start}, with the same length in working days. */
    ScheduledTask startingOn(ScheduledTask task, LocalDate start) {
        int duration = calendar.duration(task.start(), task.due());
        return new ScheduledTask(task.id(), start, calendar.addWorkingDays(start, duration - 1));
    }
}
