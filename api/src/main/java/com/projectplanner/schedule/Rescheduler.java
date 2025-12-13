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
            graph.successors(id).forEach(link -> affected.add(link.successorId()));
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
            int duration = calendar.duration(task.start(), task.due());
            LocalDate start = earliest;
            LocalDate due = calendar.addWorkingDays(start, duration - 1);
            ScheduledTask shifted = new ScheduledTask(id, start, due);
            current.put(id, shifted);
            moved.put(id, shifted);
        }
        return moved;
    }

    /** The first day {@code id} may start given all of its predecessors, or null if none constrain it. */
    LocalDate earliestStart(DependencyGraph graph, Map<Long, ScheduledTask> tasks, long id) {
        LocalDate earliest = null;
        for (Link link : graph.predecessors(id)) {
            ScheduledTask predecessor = tasks.get(link.predecessorId());
            if (predecessor == null || predecessor.due() == null) {
                continue;
            }
            LocalDate candidate = calendar.addWorkingDays(calendar.nextWorkingDay(predecessor.due()), link.lagDays());
            if (earliest == null || candidate.isAfter(earliest)) {
                earliest = candidate;
            }
        }
        return earliest;
    }
}
