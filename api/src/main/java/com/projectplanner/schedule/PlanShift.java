package com.projectplanner.schedule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Moves a whole plan so that it begins on another day. Every date keeps its
 * distance from the first day of the plan, counted in working days. So a task
 * is as long as it was, the gap between two tasks is what it was, and what the
 * {@link ScheduleCheck} says about the dates stays the same: a link that held
 * still holds, and one that did not is still short by the same number of days.
 *
 * <p>The moved plan stands on working days only. A date on a day off is taken
 * as the working day it stands for: a start as the next one, an end as the one
 * before. That is how the scheduler reads such dates already, so nothing
 * changes for a task that has at least one working day. A task that lies on
 * days off entirely becomes a one-day task on the next working day, which is
 * how {@link LinkRule#normalise} reads it in the source as well, so it is in
 * the way of the same neighbours before and after. The same goes for a task
 * whose only date is on a day off.
 *
 * <p>All of the arithmetic goes through the {@link WorkCalendar} that is passed
 * in, so a calendar with holidays moves a plan around them.
 */
public final class PlanShift {

    private final WorkCalendar calendar;
    private final Rescheduler rescheduler;
    private final LocalDate from;
    private final LocalDate to;
    private final Map<LocalDate, LocalDate> moved = new HashMap<>();

    /**
     * @param planStart the first day of the plan as it is, see {@link #firstDay}
     * @param newStart  the day it should begin on; from a day off it begins on the next working day
     */
    public PlanShift(WorkCalendar calendar, LocalDate planStart, LocalDate newStart) {
        this.calendar = calendar;
        this.rescheduler = new Rescheduler(calendar);
        this.from = calendar.onOrAfter(planStart);
        this.to = calendar.onOrAfter(newStart);
    }

    /**
     * The first working day of a plan: the earliest of the tasks' dates and of the
     * project's own start, each read as a working day. Null if nothing has a date.
     */
    public static LocalDate firstDay(WorkCalendar calendar, Collection<ScheduledTask> tasks, LocalDate projectStart) {
        LocalDate first = projectStart == null ? null : calendar.onOrAfter(projectStart);
        for (ScheduledTask task : tasks) {
            LocalDate day = task.start() != null ? calendar.onOrAfter(task.start())
                    : task.due() != null ? calendar.onOrBefore(task.due()) : null;
            if (day != null && (first == null || day.isBefore(first))) {
                first = day;
            }
        }
        return first;
    }

    /** Where a date that something starts on goes. Null stays null. */
    public LocalDate start(LocalDate date) {
        return date == null ? null : move(calendar.onOrAfter(date));
    }

    /** Where a date that something ends on goes. Null stays null. */
    public LocalDate end(LocalDate date) {
        return date == null ? null : move(calendar.onOrBefore(date));
    }

    /**
     * The task in the moved plan. With both dates the start keeps its distance and
     * the due date keeps the length, the way the rescheduler moves a task. With one
     * date that date moves, and a task without dates stays without.
     */
    public ScheduledTask task(ScheduledTask task) {
        if (task.isScheduled()) {
            return rescheduler.startingOn(task, start(task.start()));
        }
        return new ScheduledTask(task.id(), start(task.start()), end(task.due()));
    }

    private LocalDate move(LocalDate workingDay) {
        return moved.computeIfAbsent(workingDay, day -> day.isBefore(from)
                ? calendar.addWorkingDays(to, 1 - calendar.workingDaysBetween(day, from))
                : calendar.addWorkingDays(to, calendar.workingDaysBetween(from, day) - 1));
    }
}
