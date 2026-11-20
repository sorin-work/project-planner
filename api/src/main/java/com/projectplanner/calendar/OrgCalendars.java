package com.projectplanner.calendar;

import com.projectplanner.schedule.WorkCalendar;
import jakarta.enterprise.context.RequestScoped;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The working days of an organisation, read once per request: every place in a request
 * that counts working days gets the same calendar from here, and the holidays are one
 * query however many of them ask.
 */
@RequestScoped
public class OrgCalendars {

    private final Map<Long, WorkCalendar> calendars = new HashMap<>();

    public WorkCalendar of(long organisationId) {
        return calendars.computeIfAbsent(organisationId, id ->
                new WorkCalendar(Holiday.<Holiday>list("organisationId", id).stream().map(h -> h.date).toList()));
    }

    /** Forgets what was read, after a holiday was added, moved or removed in this request. */
    void changed(long organisationId) {
        calendars.remove(organisationId);
    }

    /**
     * The days each person of the organisation is away from {@code from} to {@code to}, both
     * included, by user id. Weekends and holidays are in it as well when the time off covers
     * them; whoever counts capacity skips the days that are not working days anyway.
     */
    public Map<Long, Set<LocalDate>> daysOff(long organisationId, LocalDate from, LocalDate to) {
        Map<Long, Set<LocalDate>> days = new HashMap<>();
        TimeOff.<TimeOff>list("organisationId = ?1 and firstDay <= ?2 and lastDay >= ?3", organisationId, to, from)
                .forEach(t -> {
                    Set<LocalDate> own = days.computeIfAbsent(t.userId, id -> new HashSet<>());
                    LocalDate first = t.firstDay.isBefore(from) ? from : t.firstDay;
                    LocalDate last = t.lastDay.isAfter(to) ? to : t.lastDay;
                    for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                        own.add(d);
                    }
                });
        return days;
    }
}
