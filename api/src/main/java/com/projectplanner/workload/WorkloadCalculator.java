package com.projectplanner.workload;

import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocated hours per person per week. Each open task's estimate is spread
 * evenly over the working days between its start and due date; tasks without
 * dates are reported separately as unscheduled work.
 */
public final class WorkloadCalculator {

    private final WorkCalendar calendar;

    public WorkloadCalculator(WorkCalendar calendar) {
        this.calendar = calendar;
    }

    public List<MemberLoad> calculate(Collection<Person> people, Collection<Assignment> assignments,
                                      LocalDate from, int weeks) {
        LocalDate firstWeek = WorkCalendar.weekStart(from);
        LocalDate end = firstWeek.plusWeeks(weeks).minusDays(1);

        Map<Long, double[]> hours = new HashMap<>();
        Map<Long, Double> unscheduled = new HashMap<>();
        Map<Long, List<Long>> tasksPerWeek = new HashMap<>();
        for (Person person : people) {
            hours.put(person.userId(), new double[weeks]);
            unscheduled.put(person.userId(), 0.0);
        }

        for (Assignment a : assignments) {
            double[] perWeek = hours.get(a.userId());
            if (perWeek == null || a.done() || a.estimateHours() <= 0) {
                continue;
            }
            LocalDate start = a.start() != null ? a.start() : a.due();
            LocalDate due = a.due() != null ? a.due() : a.start();
            if (start == null) {
                unscheduled.merge(a.userId(), a.estimateHours(), Double::sum);
                continue;
            }
            int days = calendar.workingDaysBetween(start, due);
            if (days == 0) {
                // Weekend-only task: book it on the next working day.
                start = calendar.onOrAfter(start);
                due = start;
                days = 1;
            }
            double perDay = a.estimateHours() / days;
            LocalDate d = start.isBefore(firstWeek) ? firstWeek : start;
            LocalDate last = due.isAfter(end) ? end : due;
            while (!d.isAfter(last)) {
                if (calendar.isWorkingDay(d)) {
                    int week = (int) ((d.toEpochDay() - firstWeek.toEpochDay()) / 7);
                    perWeek[week] += perDay;
                    List<Long> ids = tasksPerWeek.computeIfAbsent(key(a.userId(), week), k -> new ArrayList<>());
                    if (!ids.contains(a.taskId())) {
                        ids.add(a.taskId());
                    }
                }
                d = d.plusDays(1);
            }
        }

        List<MemberLoad> result = new ArrayList<>();
        for (Person person : people) {
            double[] perWeek = hours.get(person.userId());
            List<WeekLoad> weekLoads = new ArrayList<>();
            for (int w = 0; w < weeks; w++) {
                double allocated = round(perWeek[w]);
                weekLoads.add(new WeekLoad(firstWeek.plusWeeks(w), allocated, person.capacityHours(),
                        allocated > person.capacityHours(),
                        tasksPerWeek.getOrDefault(key(person.userId(), w), List.of())));
            }
            result.add(new MemberLoad(person.userId(), person.capacityHours(), weekLoads,
                    round(unscheduled.get(person.userId()))));
        }
        return result;
    }

    private static long key(long userId, int week) {
        return userId * 1000 + week;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    public record Person(long userId, double capacityHours) {
    }

    public record Assignment(long taskId, long userId, double estimateHours, LocalDate start, LocalDate due, boolean done) {
    }

    public record WeekLoad(LocalDate weekStart, double allocatedHours, double capacityHours,
                           boolean overallocated, List<Long> taskIds) {
    }

    public record MemberLoad(long userId, double capacityHours, List<WeekLoad> weeks, double unscheduledHours) {
    }
}
