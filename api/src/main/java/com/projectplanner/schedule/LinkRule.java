package com.projectplanner.schedule;

import java.time.LocalDate;

/**
 * The one rule for what a dependency asks of its successor. Everything that looks at
 * links goes through it: the rescheduler, the schedule check and its suggested dates,
 * and the critical path, which counts in working-day offsets instead of dates and has
 * its own two methods here that say the same thing in those numbers.
 *
 * <p>The four types, with a lag of L working days (negative for a lead):
 * <ul>
 *   <li>FS: start no earlier than L working days after the first working day after
 *       the predecessor is due. With L = -1 that is the predecessor's last day.</li>
 *   <li>SS: start no earlier than L working days after the predecessor starts.</li>
 *   <li>FF: be due no earlier than L working days after the predecessor is due.</li>
 *   <li>SF: be due no earlier than L working days after the working day before the
 *       predecessor starts. With L = 0 the successor ends the day before, the way FS
 *       with L = 0 starts the day after; L = 1 lets the two share that first day.</li>
 * </ul>
 * A link that holds the finish is turned into a start by keeping the successor's
 * length in working days, so a task is always moved whole. A date on a day off
 * counts as the working day it stands for: a start as the next one, a due date as
 * the one before. A task that lies on days off entirely has no working day of its own,
 * so it is taken as a one-day task on the next working day, by the rescheduler, the
 * check and the critical path alike ({@link #normalise}). All the date arithmetic is
 * {@link WorkCalendar}'s.
 */
public final class LinkRule {

    private final WorkCalendar calendar;

    public LinkRule(WorkCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * The first day {@code successor} may start because of {@code link}, or null when
     * the predecessor lacks the date the link counts from (a due date for FS and FF, a
     * start for SS and SF): then the link says nothing.
     *
     * @param successor the successor's current dates, for its length. A successor with
     *                  only one date is a one-day task.
     */
    public LocalDate earliestStart(Link link, ScheduledTask predecessor, ScheduledTask successor) {
        if (predecessor == null) {
            return null;
        }
        predecessor = normalise(predecessor);
        DependencyType type = link.type();
        LocalDate from = type.fromStart() ? predecessor.start() : predecessor.due();
        if (from == null) {
            return null;
        }
        LocalDate anchor = switch (type) {
            // The day after the predecessor's last working day. Nothing is added for a lag of
            // zero, so the anchor has to be a working day already: addWorkingDays keeps any date
            // it is given for zero, a Saturday included.
            case FS -> calendar.nextWorkingDay(from);
            case SS -> calendar.onOrAfter(from);
            // The working day before the predecessor's first one.
            case SF -> calendar.addWorkingDays(calendar.onOrAfter(from), -1);
            case FF -> calendar.onOrBefore(from);
        };
        LocalDate bound = calendar.addWorkingDays(anchor, link.lagDays());
        if (!type.constrainsFinish()) {
            return bound;
        }
        return calendar.addWorkingDays(bound, -(length(successor) - 1));
    }

    /**
     * The same rule in working-day offsets, as the critical path counts: the earliest
     * start of the successor, given the predecessor's early start and finish and the
     * successor's length.
     */
    public static int earliestStartOffset(Link link, int predecessorStart, int predecessorFinish, int successorLength) {
        return switch (link.type()) {
            case FS -> predecessorFinish + 1 + link.lagDays();
            case SS -> predecessorStart + link.lagDays();
            case FF -> predecessorFinish + link.lagDays() - successorLength + 1;
            case SF -> predecessorStart - 1 + link.lagDays() - successorLength + 1;
        };
    }

    /**
     * The rule read backwards: the latest finish the predecessor may have without
     * pushing the successor, given the successor's late start and finish and the
     * predecessor's length.
     */
    public static int latestFinishOffset(Link link, int successorStart, int successorFinish, int predecessorLength) {
        return switch (link.type()) {
            case FS -> successorStart - 1 - link.lagDays();
            case SS -> successorStart - link.lagDays() + predecessorLength - 1;
            case FF -> successorFinish - link.lagDays();
            case SF -> successorFinish + 1 - link.lagDays() + predecessorLength - 1;
        };
    }

    private int length(ScheduledTask task) {
        return task == null || !task.isScheduled() ? 1 : calendar.duration(task.start(), task.due());
    }

    /**
     * A task as the rules count it. A task whose dates hold no working day, a Saturday
     * milestone or a weekend-only task, becomes a one-day task on the next working day;
     * so does a single date on a day off. Anything with a working day in it is left as
     * it is, and its days off are read by the anchors above.
     */
    public ScheduledTask normalise(ScheduledTask task) {
        if (task == null) {
            return null;
        }
        LocalDate first = task.start() != null ? task.start() : task.due();
        LocalDate last = task.due() != null ? task.due() : task.start();
        if (first == null || calendar.workingDaysBetween(first, last) > 0) {
            return task;
        }
        LocalDate day = calendar.onOrAfter(first);
        return new ScheduledTask(task.id(), task.start() == null ? null : day, task.due() == null ? null : day);
    }
}
