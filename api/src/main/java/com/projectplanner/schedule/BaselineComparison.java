package com.projectplanner.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a plan with a baseline, a copy of its dates saved earlier. Per task:
 * how far the start and the finish have moved, in working days. For the plan as
 * a whole: how far its end has moved and how the total estimate has changed.
 *
 * <p>Dates are read the way the scheduler reads them. A start on a day off is
 * the next working day and a finish on a day off the one before, so moving a due
 * date from Friday to Saturday is not a slip.
 */
public final class BaselineComparison {

    public enum Change {
        /** In both, and neither the start nor the finish is on another working day. */
        UNCHANGED,
        /** Finishes later. If the finish did not move: starts later. */
        SLIPPED,
        /** Finishes earlier. If the finish did not move: starts earlier. */
        PULLED_IN,
        /** Created since the baseline, with or without dates, or there then without dates and given some since. */
        ADDED,
        /** Deleted since the baseline, with or without dates, or there now with the dates it had cleared. */
        REMOVED
    }

    /** A task as the baseline or the plan has it. */
    public record Entry(long id, Long parentId, String title, LocalDate start, LocalDate due, double estimateHours, boolean milestone) {

        boolean dated() {
            return start != null || due != null;
        }

        /** A task with one date is a one-day task on it, as on the timeline. */
        LocalDate first() {
            return start != null ? start : due;
        }

        LocalDate last() {
            return due != null ? due : start;
        }
    }

    /**
     * @param title          the title the task has now, or had in the baseline if it is gone
     * @param existedThen    false for a task that was created after the baseline
     * @param existsNow      false for a task that has been deleted since
     * @param startSlipDays  working days the start has moved, later being positive; null unless both have dates
     * @param finishSlipDays the same for the finish
     */
    public record TaskChange(long taskId, String title, boolean milestone, Change change, boolean existedThen, boolean existsNow,
                             LocalDate baselineStart, LocalDate baselineDue, LocalDate currentStart, LocalDate currentDue,
                             Integer startSlipDays, Integer finishSlipDays) {
    }

    /** One date then and now. {@code slipDays} is null unless both are there. */
    public record DateChange(LocalDate baseline, LocalDate current, Integer slipDays, Change change) {
    }

    /**
     * @param projectEnd            the project's own end date
     * @param planEnd               the latest due date of any task
     * @param counts                how many tasks there are of each kind of change
     * @param baselineEstimateHours estimates of the tasks that carry them (no parents, no milestones), then
     * @param currentEstimateHours  and now
     */
    public record Result(List<TaskChange> tasks, DateChange projectEnd, DateChange planEnd, Map<Change, Integer> counts,
                         double baselineEstimateHours, double currentEstimateHours) {
    }

    private final WorkCalendar calendar;

    public BaselineComparison(WorkCalendar calendar) {
        this.calendar = calendar;
    }

    public Result compare(Collection<Entry> baseline, Collection<Entry> current, LocalDate baselineProjectEnd, LocalDate currentProjectEnd) {
        List<TaskChange> tasks = changes(baseline, current);

        Map<Change, Integer> counts = new EnumMap<>(Change.class);
        for (Change change : Change.values()) {
            counts.put(change, 0);
        }
        tasks.forEach(t -> counts.merge(t.change(), 1, Integer::sum));

        return new Result(List.copyOf(tasks), finish(baselineProjectEnd, currentProjectEnd),
                finish(latestDue(baseline), latestDue(current)), counts, estimate(baseline), estimate(current));
    }

    /**
     * Phases then and now, by the same rule as tasks: an entry per phase with its title as
     * the name, its start and its end. Phases carry no estimate, and their changes are not
     * counted with the tasks'.
     */
    public List<TaskChange> comparePhases(Collection<Entry> baseline, Collection<Entry> current) {
        return changes(baseline, current);
    }

    /** One change per item that is in either list, in the order of their first day. */
    private List<TaskChange> changes(Collection<Entry> baseline, Collection<Entry> current) {
        Map<Long, Entry> then = byId(baseline);
        Map<Long, Entry> now = byId(current);
        List<TaskChange> changes = new ArrayList<>();
        for (Entry item : now.values()) {
            changes.add(change(then.get(item.id()), item));
        }
        for (Entry item : then.values()) {
            if (!now.containsKey(item.id())) {
                changes.add(change(item, null));
            }
        }
        changes.sort(Comparator.comparing(BaselineComparison::firstDay, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TaskChange::taskId));
        return changes;
    }

    private TaskChange change(Entry then, Entry now) {
        Entry named = now != null ? now : then;
        boolean datedThen = then != null && then.dated();
        boolean datedNow = now != null && now.dated();
        Integer startSlip = null;
        Integer finishSlip = null;
        Change change;
        if (then == null) {
            // Created since. It is new whether it has dates or not, never "unchanged".
            change = Change.ADDED;
        } else if (now == null) {
            // Deleted since, with or without dates.
            change = Change.REMOVED;
        } else if (datedThen && datedNow) {
            startSlip = calendar.slip(calendar.onOrAfter(then.first()), calendar.onOrAfter(now.first()));
            finishSlip = calendar.slip(calendar.onOrBefore(then.last()), calendar.onOrBefore(now.last()));
            // The finish decides. A task that ends when it was going to has only slipped if it starts later.
            int decisive = finishSlip != 0 ? finishSlip : startSlip;
            change = decisive > 0 ? Change.SLIPPED : decisive < 0 ? Change.PULLED_IN : Change.UNCHANGED;
        } else if (datedNow) {
            change = Change.ADDED;
        } else if (datedThen) {
            change = Change.REMOVED;
        } else {
            // There then and now, without dates either time: nothing has changed that a baseline keeps.
            change = Change.UNCHANGED;
        }
        return new TaskChange(named.id(), named.title(), named.milestone(), change, then != null, now != null,
                then == null ? null : then.start(), then == null ? null : then.due(),
                now == null ? null : now.start(), now == null ? null : now.due(), startSlip, finishSlip);
    }

    private DateChange finish(LocalDate then, LocalDate now) {
        if (then == null || now == null) {
            return new DateChange(then, now, null, now != null ? Change.ADDED : then != null ? Change.REMOVED : Change.UNCHANGED);
        }
        int slip = calendar.slip(calendar.onOrBefore(then), calendar.onOrBefore(now));
        return new DateChange(then, now, slip, slip > 0 ? Change.SLIPPED : slip < 0 ? Change.PULLED_IN : Change.UNCHANGED);
    }

    private static LocalDate firstDay(TaskChange task) {
        if (task.currentStart() != null || task.currentDue() != null) {
            return task.currentStart() != null ? task.currentStart() : task.currentDue();
        }
        return task.baselineStart() != null ? task.baselineStart() : task.baselineDue();
    }

    private static LocalDate latestDue(Collection<Entry> tasks) {
        return tasks.stream().map(Entry::due).filter(d -> d != null).max(Comparator.naturalOrder()).orElse(null);
    }

    /** The same tasks the progress figures count: those without subtasks, milestones left out. */
    private static double estimate(Collection<Entry> tasks) {
        Set<Long> parents = new HashSet<>();
        tasks.forEach(t -> {
            if (t.parentId() != null) {
                parents.add(t.parentId());
            }
        });
        return tasks.stream().filter(t -> !parents.contains(t.id()) && !t.milestone()).mapToDouble(Entry::estimateHours).sum();
    }

    private static Map<Long, Entry> byId(Collection<Entry> tasks) {
        Map<Long, Entry> byId = new LinkedHashMap<>();
        tasks.forEach(t -> byId.put(t.id(), t));
        return byId;
    }
}
