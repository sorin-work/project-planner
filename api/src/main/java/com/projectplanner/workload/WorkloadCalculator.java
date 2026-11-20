package com.projectplanner.workload;

import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allocated hours per person per week. Each open task's estimate is spread
 * evenly over the working days between its start and due date; tasks without
 * dates are reported separately as unscheduled work.
 *
 * <p>Capacity is per week. Every working day is a fifth of the weekly hours, so a
 * holiday takes a fifth off for everybody and a day off a fifth off for that person.
 * A task is still spread over its own working days, not around its assignee's days off.
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
                LocalDate monday = firstWeek.plusWeeks(w);
                LocalDate sunday = monday.plusDays(6);
                double capacity = round(capacityMinutes(person, monday, sunday) / 60.0);
                weekLoads.add(new WeekLoad(monday, allocated, capacity, allocated > capacity,
                        tasksPerWeek.getOrDefault(key(person.userId(), w), List.of()),
                        holidays(monday, sunday), daysOff(person, monday, sunday)));
            }
            result.add(new MemberLoad(person.userId(), person.capacityHours(), weekLoads,
                    round(unscheduled.get(person.userId()))));
        }
        return result;
    }

    /**
     * What a person can work from {@code from} to {@code to}, both included, in minutes:
     * their weekly hours spread evenly over the five weekdays, for the working days of the
     * range that they are not away. A whole week without holidays or time off comes to
     * exactly the weekly hours. The weeks of the grid and the reports ask here, so that
     * there is one definition.
     */
    public long capacityMinutes(Person person, LocalDate from, LocalDate to) {
        int days = calendar.workingDaysBetween(from, to) - daysOff(person, from, to);
        return Math.round(person.capacityHours() * 60 * days / WorkCalendar.WORKING_DAYS_PER_WEEK);
    }

    /** Holidays on weekdays from {@code from} to {@code to}. */
    private int holidays(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (calendar.isHoliday(d)) {
                count++;
            }
        }
        return count;
    }

    /** The person's days off from {@code from} to {@code to} that would have been working days. */
    private int daysOff(Person person, LocalDate from, LocalDate to) {
        return (int) person.daysOff().stream()
                .filter(d -> !d.isBefore(from) && !d.isAfter(to) && calendar.isWorkingDay(d))
                .count();
    }

    private static long key(long userId, int week) {
        return userId * 1000 + week;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** {@code daysOff} may hold weekends and holidays too, they are not counted twice. */
    public record Person(long userId, double capacityHours, Set<LocalDate> daysOff) {

        public Person(long userId, double capacityHours) {
            this(userId, capacityHours, Set.of());
        }
    }

    public record Assignment(long taskId, long userId, double estimateHours, LocalDate start, LocalDate due, boolean done) {
    }

    /**
     * @param capacityHours the week's capacity, lower than the weekly hours by a fifth for
     *                      each holiday and each day off
     */
    public record WeekLoad(LocalDate weekStart, double allocatedHours, double capacityHours,
                           boolean overallocated, List<Long> taskIds, int holidays, int daysOff) {
    }

    public record MemberLoad(long userId, double capacityHours, List<WeekLoad> weeks, double unscheduledHours) {
    }
}
