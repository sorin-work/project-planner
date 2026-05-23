package com.projectplanner.report;

import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Progress and schedule variance from task estimates. Progress counts finished
 * work only; earned value for the schedule uses the 50/50 rule (half a task's
 * estimate is earned when work starts, the rest when it is done), which tracks
 * long tasks better than all-or-nothing. Parent tasks are left out when they
 * have subtasks, so work isn't counted twice.
 */
public final class ProgressCalculator {

    private final WorkCalendar calendar;

    public ProgressCalculator(WorkCalendar calendar) {
        this.calendar = calendar;
    }

    public Progress calculate(Collection<TaskFacts> tasks, LocalDate today) {
        Set<Long> parents = new HashSet<>();
        for (TaskFacts task : tasks) {
            if (task.parentId() != null) {
                parents.add(task.parentId());
            }
        }
        List<TaskFacts> leaves = tasks.stream().filter(t -> !parents.contains(t.id()) && !t.milestone()).toList();

        double total = 0;
        double done = 0;
        double planned = 0;
        double earnedScheduled = 0;
        int doneCount = 0;
        for (TaskFacts task : leaves) {
            total += task.estimateHours();
            if (task.done()) {
                done += task.estimateHours();
                doneCount++;
            }
            if (task.start() != null && task.due() != null) {
                planned += task.estimateHours() * plannedFraction(task, today);
                if (task.done()) {
                    earnedScheduled += task.estimateHours();
                } else if (task.started()) {
                    earnedScheduled += task.estimateHours() / 2;
                }
            }
        }

        double progress;
        if (total > 0) {
            progress = done / total * 100;
        } else {
            // Nothing is estimated yet: fall back to counting tasks.
            progress = leaves.isEmpty() ? 0 : (double) doneCount / leaves.size() * 100;
        }
        double plannedPct = total > 0 ? planned / total * 100 : 0;
        Double spi = planned > 0 ? earnedScheduled / planned : null;

        int overdue = 0;
        int open = 0;
        for (TaskFacts task : tasks) {
            if (!task.done()) {
                open++;
                if (task.due() != null && task.due().isBefore(today)) {
                    overdue++;
                }
            }
        }

        return new Progress(round(total), round(done), round(progress), round(plannedPct),
                round(earnedScheduled), round(planned), round(earnedScheduled - planned),
                spi == null ? null : round(spi), overdue, open, tasks.size() - open);
    }

    /** Share of the task's working days that have passed by the end of {@code today}. */
    double plannedFraction(TaskFacts task, LocalDate today) {
        if (today.isBefore(task.start())) {
            return 0;
        }
        if (!today.isBefore(task.due())) {
            return 1;
        }
        int duration = calendar.duration(task.start(), task.due());
        int elapsed = calendar.workingDaysBetween(task.start(), today);
        return Math.min(1, (double) elapsed / duration);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * @param scheduleVarianceHours earned minus planned hours; negative means behind
     * @param spi schedule performance index (earned / planned), null before any work is planned
     */
    public record Progress(double totalEstimateHours, double doneEstimateHours, double progressPct,
                           double plannedPct, double earnedHours, double plannedHours,
                           double scheduleVarianceHours, Double spi, int overdueTasks,
                           int openTasks, int doneTasks) {
    }
}
