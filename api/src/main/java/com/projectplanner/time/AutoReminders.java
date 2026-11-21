package com.projectplanner.time;

import com.projectplanner.calendar.OrgCalendars;
import com.projectplanner.org.Organisation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The timesheet reminders that go out without a manager clicking "Remind all", for the
 * organisations that switched them on. On the first working day of the week, from eight in the
 * morning to noon in the organisation's time zone, the people who logged time last week and
 * did not submit it are reminded once. The organisation's row records the day, and only the
 * run that moves it on sends, so two instances of the API never both do.
 */
@ApplicationScoped
public class AutoReminders {

    private static final Logger LOG = Logger.getLogger(AutoReminders.class);
    static final LocalTime FROM = LocalTime.of(8, 0);
    static final LocalTime UNTIL = LocalTime.NOON;

    private final TimesheetService timesheets;
    private final OrgCalendars calendars;
    private final Clock clock;

    public AutoReminders(TimesheetService timesheets, OrgCalendars calendars, Clock clock) {
        this.timesheets = timesheets;
        this.calendars = calendars;
        this.clock = clock;
    }

    @Scheduled(identity = "auto-reminders", every = "60s", delayed = "50s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        run();
    }

    /** One round: answers how many people were reminded. */
    @ActivateRequestContext
    public int run() {
        List<Organisation> organisations = QuarkusTransaction.requiringNew().call(
                () -> Organisation.<Organisation>list("autoReminders = true"));
        int reminded = 0;
        for (Organisation org : organisations) {
            ZonedDateTime local = clock.instant().atZone(org.zone());
            LocalTime time = local.toLocalTime();
            LocalDate today = local.toLocalDate();
            if (time.isBefore(FROM) || !time.isBefore(UNTIL)) {
                continue;
            }
            boolean due = QuarkusTransaction.requiringNew().call(
                    () -> TimesheetService.firstWorkingDayOfWeek(calendars.of(org.id), today));
            if (!due || !claim(org.id, today)) {
                continue;
            }
            List<Long> people = QuarkusTransaction.requiringNew().call(() -> timesheets.autoRemind(org.id));
            LOG.debugf("Reminded %d people of organisation %d of last week's timesheet", people.size(), org.id);
            reminded += people.size();
        }
        return reminded;
    }

    /** Moves the organisation's reminder day on to today. False when it already is: another run sent them. */
    private static boolean claim(long organisationId, LocalDate today) {
        return QuarkusTransaction.requiringNew().call(() -> Organisation.update(
                "remindersSentOn = ?1 where id = ?2 and (remindersSentOn is null or remindersSentOn < ?1)",
                today, organisationId) == 1);
    }
}
