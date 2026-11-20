package com.projectplanner.schedule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pushes dependants later when a task moves so that every finish-to-start link
 * still holds. Tasks are only ever moved forward and keep their duration in
 * working days; dependants that already start late enough are left alone.
 */
public final class Rescheduler {

    private final WorkCalendar calendar;

    public Rescheduler(WorkCalendar calendar) {
        this.calendar = calendar;
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

    /** The first day {@code id} may start given all of its predecessors, or null if none constrain it. */
    LocalDate earliestStart(DependencyGraph graph, Map<Long, ScheduledTask> tasks, long id) {
        LocalDate earliest = null;
        for (Link link : graph.predecessors(id)) {
            LocalDate candidate = earliestStart(link, tasks.get(link.predecessorId()));
            if (candidate != null && (earliest == null || candidate.isAfter(earliest))) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    /**
     * The first day the successor of {@code link} may start: the working day after
     * the predecessor is due, plus the lag. Null if the predecessor has no due date,
     * because then the link says nothing.
     */
    LocalDate earliestStart(Link link, ScheduledTask predecessor) {
        if (predecessor == null || predecessor.due() == null) {
            return null;
        }
        return calendar.addWorkingDays(calendar.nextWorkingDay(predecessor.due()), link.lagDays());
    }

    /** {@code task} moved so that it starts on {@code start}, with the same length in working days. */
    ScheduledTask startingOn(ScheduledTask task, LocalDate start) {
        int duration = calendar.duration(task.start(), task.due());
        return new ScheduledTask(task.id(), start, calendar.addWorkingDays(start, duration - 1));
    }
}
