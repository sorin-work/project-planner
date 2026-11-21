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
            if (!due) {
                continue;
            }
            try {
                reminded += QuarkusTransaction.requiringNew().call(() -> remindOnce(org.id, today));
            } catch (RuntimeException e) {
                // The day was not moved on, so the next run tries again this morning.
                LOG.errorf("The automatic timesheet reminders of organisation %d failed and are tried again on the next run: %s",
                        org.id, e.getClass().getSimpleName());
                LOG.debug("The automatic reminders failed", e);
            }
        }
        return reminded;
    }

    /**
     * Moves the organisation's reminder day on to today and sends the reminders, in one
     * transaction. The conditional update is the once-only guard: a second run waits for the
     * row and then finds the day taken. When the reminders fail, the day goes back with them.
     */
    private int remindOnce(long organisationId, LocalDate today) {
        int claimed = Organisation.update("remindersSentOn = ?1 where id = ?2 and (remindersSentOn is null or remindersSentOn < ?1)",
                today, organisationId);
        if (claimed == 0) {
            return 0;
        }
        List<Long> people = failure.apply(timesheets.autoRemind(organisationId));
        LOG.debugf("Reminded %d people of organisation %d of last week's timesheet", people.size(), organisationId);
        return people.size();
    }

    /** For tests: what happens to the people about to be reminded, to make a run fail half way. */
    void failWith(java.util.function.UnaryOperator<List<Long>> hook) {
        failure = hook;
    }

    private volatile java.util.function.UnaryOperator<List<Long>> failure = people -> people;
}
