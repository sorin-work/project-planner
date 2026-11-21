package com.projectplanner.time;

import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who still owes a timesheet for a week, and who may be reminded about it. A
 * week is missing while it is a draft (which includes never submitted) or was
 * sent back. Only weeks that are over can be late. Reminders are limited per
 * person so that nobody's inbox fills up with them.
 */
public final class MissingTimesheets {

    /** Reminders one person can get in a day, over all weeks and whoever sends them. */
    public static final int MAX_REMINDERS_PER_DAY = 5;

    private MissingTimesheets() {
    }

    /**
     * A team member, with the day they joined in the organisation's time zone and what they
     * could work in the week: their weekly hours less the holidays and their days off, as the
     * workload counts it. {@code away} is true when their time off covers every working day.
     */
    public record Person(long userId, String name, LocalDate joined, double capacityHours, boolean away) {
    }

    /**
     * @param capacityHours the week's capacity, lower than the weekly hours by a fifth for each
     *                      holiday and each day off
     * @param away          time off on every working day of the week. They stay on the list, since
     *                      somebody may have worked anyway.
     */
    public record Missing(long userId, String userName, TimesheetStatus status, int totalMinutes,
                          double capacityHours, boolean away, boolean remindedToday) {
    }

    /** Why somebody on a reminder request got no reminder. */
    public enum Skip {
        /** Not on the list: submitted or approved by now, joined later, or not on the team at all. */
        NOT_MISSING,
        YOURSELF,
        /** Already reminded about this week today. */
        ALREADY_REMINDED,
        /** Reminded about {@link #MAX_REMINDERS_PER_DAY} other weeks today. */
        DAILY_LIMIT
    }

    /** Monday of the most recent week that is over on {@code today}. */
    public static LocalDate latestEndedWeek(LocalDate today) {
        return WorkCalendar.weekStart(today).minusWeeks(1);
    }

    /**
     * The people whose week starting on {@code weekStart} is missing, by name.
     * Somebody who joined after that week is left out; without a status the week is a draft.
     */
    public static List<Missing> find(LocalDate weekStart, Collection<Person> team, Map<Long, TimesheetStatus> statuses,
                                     Map<Long, Integer> minutes, Set<Long> remindedToday) {
        LocalDate weekEnd = weekStart.plusDays(6);
        List<Missing> result = new ArrayList<>();
        for (Person person : team) {
            TimesheetStatus status = statuses.getOrDefault(person.userId(), TimesheetStatus.DRAFT);
            if (status.locked() || person.joined().isAfter(weekEnd)) {
                continue;
            }
            result.add(new Missing(person.userId(), person.name(), status, minutes.getOrDefault(person.userId(), 0),
                    person.capacityHours(), person.away(), remindedToday.contains(person.userId())));
        }
        result.sort(Comparator.comparing(Missing::userName, String.CASE_INSENSITIVE_ORDER).thenComparing(Missing::userId));
        return result;
    }

    /**
     * Why {@code missing} gets no reminder from {@code actorId}, or empty when one may go out.
     * {@code missing} is null for somebody who is not on the list.
     */
    public static Optional<Skip> skipReason(Missing missing, long actorId) {
        if (missing == null) {
            return Optional.of(Skip.NOT_MISSING);
        }
        if (missing.userId() == actorId) {
            return Optional.of(Skip.YOURSELF);
        }
        return missing.remindedToday() ? Optional.of(Skip.ALREADY_REMINDED) : Optional.empty();
    }

    /**
     * The limits on reminders for one person: once per week and day, and no more than
     * {@link #MAX_REMINDERS_PER_DAY} a day. {@code remindedToday} holds the weeks they were
     * reminded about today.
     */
    public static Optional<Skip> reminderLimit(LocalDate weekStart, Collection<LocalDate> remindedToday) {
        if (remindedToday.contains(weekStart)) {
            return Optional.of(Skip.ALREADY_REMINDED);
        }
        return remindedToday.size() >= MAX_REMINDERS_PER_DAY ? Optional.of(Skip.DAILY_LIMIT) : Optional.empty();
    }
}
