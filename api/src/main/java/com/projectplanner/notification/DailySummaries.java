package com.projectplanner.notification;

import com.projectplanner.auth.User;
import com.projectplanner.calendar.Holiday;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.mail.Mails;
import com.projectplanner.mail.Mails.Line;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.time.Timesheet;
import com.projectplanner.time.TimesheetStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The morning summary: one mail a day per person and organisation, from eight in the morning
 * in the organisation's time zone, on its working days. It counts the person's tasks due today
 * and overdue and, for those who manage, the timesheets waiting for review, with a link to each
 * page and no titles. Nothing to say means no mail.
 * <p>
 * The job runs once a minute. A summary that could not go out at eight, because the API was
 * down, goes out later that morning, and not at all from noon on. Eight o'clock is worked out
 * from the zone on the day, so daylight saving moves it in UTC and nowhere else. Before a
 * summary is sent its row in {@code daily_summaries} is written in a transaction of its own:
 * when two instances of the API take on the same person at once, the unique key lets one of
 * them through and the other leaves it.
 */
@ApplicationScoped
public class DailySummaries {

    private static final Logger LOG = Logger.getLogger(DailySummaries.class);
    static final LocalTime FROM = LocalTime.of(8, 0);
    static final LocalTime UNTIL = LocalTime.NOON;
    private static final String KIND = "daily summary";

    private final MailSettings settings;
    private final Mails mails;
    private final MailOutbox outbox;
    private final NotificationPreferences preferences;
    private final UnsubscribeLinks unsubscribe;
    private final Clock clock;

    public DailySummaries(MailSettings settings, Mails mails, MailOutbox outbox, NotificationPreferences preferences,
                          UnsubscribeLinks unsubscribe, Clock clock) {
        this.settings = settings;
        this.mails = mails;
        this.outbox = outbox;
        this.preferences = preferences;
        this.unsubscribe = unsubscribe;
        this.clock = clock;
    }

    @Scheduled(identity = "daily-summaries", every = "60s", delayed = "45s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        run();
    }

    /** One round: answers how many summaries went out. */
    public int run() {
        if (!settings.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Organisation> organisations = QuarkusTransaction.requiringNew().call(() -> Organisation.<Organisation>listAll());
        int sent = 0;
        for (Organisation org : organisations) {
            LocalDate today = morningOf(org, now);
            if (today != null) {
                sent += send(org.id, today);
            }
        }
        return sent;
    }

    /**
     * The organisation's day when {@code now} is in its summary morning, from eight to noon on a
     * working day, or null when it is not.
     */
    static LocalDate morningOf(Organisation org, Instant now) {
        ZonedDateTime local = now.atZone(org.zone());
        LocalTime time = local.toLocalTime();
        if (time.isBefore(FROM) || !time.isBefore(UNTIL)) {
            return null;
        }
        LocalDate today = local.toLocalDate();
        boolean workingDay = QuarkusTransaction.requiringNew().call(() -> new WorkCalendar(
                Holiday.<Holiday>list("organisationId = ?1 and date = ?2", org.id, today).stream().map(h -> h.date).toList())
                .isWorkingDay(today));
        return workingDay ? today : null;
    }

    /** What one person is told: the three counts. */
    record Counts(int dueToday, int overdue, int toReview) {

        boolean empty() {
            return dueToday == 0 && overdue == 0 && toReview == 0;
        }
    }

    private record Recipient(long userId, String email, Counts counts) {
    }

    private int send(long organisationId, LocalDate today) {
        List<Recipient> recipients = QuarkusTransaction.requiringNew().call(() -> recipients(organisationId, today));
        int sent = 0;
        for (Recipient recipient : recipients) {
            if (!claim(organisationId, recipient.userId(), today)) {
                continue;
            }
            if (recipient.counts().empty()) {
                continue;
            }
            if (outbox.deliver(KIND, mail(organisationId, recipient))) {
                QuarkusTransaction.requiringNew().run(() -> DailySummary.update(
                        "sentAt = ?1 where organisationId = ?2 and userId = ?3 and day = ?4",
                        clock.instant(), organisationId, recipient.userId(), today));
                sent++;
            } else {
                LOG.warnf("The morning summary for user %d could not be sent; it is not tried again today", recipient.userId());
            }
        }
        return sent;
    }

    /** The people of the organisation who want a summary and have not been taken on today, with their counts. */
    private List<Recipient> recipients(long organisationId, LocalDate today) {
        Set<Long> taken = DailySummary.<DailySummary>list("organisationId = ?1 and day = ?2", organisationId, today).stream()
                .map(s -> s.userId).collect(Collectors.toSet());
        List<Membership> members = Membership.forOrganisation(organisationId).stream()
                .filter(m -> !taken.contains(m.userId)).toList();
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, Boolean> manages = new HashMap<>();
        members.forEach(m -> manages.put(m.userId, m.role.canManage()));
        Map<Long, Boolean> wanted = preferences.summaryWanted(manages);
        List<Membership> asked = members.stream().filter(m -> wanted.get(m.userId)).toList();
        if (asked.isEmpty()) {
            return List.of();
        }

        // Every open task that is due by today in a project that is not done, with whom it is assigned to.
        Map<Long, Project> projects = new HashMap<>();
        Project.<Project>list("organisationId = ?1 and status <> ?2", organisationId, ProjectStatus.DONE)
                .forEach(p -> projects.put(p.id, p));
        List<Object[]> due = projects.isEmpty() ? List.of() : Project.getEntityManager()
                .createQuery("select t.assigneeId, t.projectId, t.dueDate from Task t where t.projectId in :projects"
                        + " and t.status <> :done and t.assigneeId in :people and t.dueDate <= :today", Object[].class)
                .setParameter("projects", projects.keySet()).setParameter("done", TaskStatus.DONE)
                .setParameter("people", asked.stream().map(m -> m.userId).toList()).setParameter("today", today)
                .getResultList();
        List<Long> submitted = Timesheet.<Timesheet>list("organisationId = ?1 and status = ?2", organisationId,
                TimesheetStatus.SUBMITTED).stream().map(t -> t.userId).toList();
        Map<Long, String> emails = new HashMap<>();
        User.<User>list("id in ?1", asked.stream().map(m -> m.userId).toList()).forEach(u -> emails.put(u.id, u.email));

        List<Recipient> result = new ArrayList<>();
        for (Membership m : asked) {
            int dueToday = 0;
            int overdue = 0;
            for (Object[] row : due) {
                Project project = projects.get((Long) row[1]);
                if (!Objects.equals(row[0], m.userId) || !OrgAccess.canSee(m, project)) {
                    continue;
                }
                if (today.equals(row[2])) {
                    dueToday++;
                } else {
                    overdue++;
                }
            }
            // A manager does not review their own week, an owner does.
            int toReview = !m.role.canManage() ? 0
                    : (int) submitted.stream().filter(owner -> m.role == Role.OWNER || !owner.equals(m.userId)).count();
            result.add(new Recipient(m.userId, emails.get(m.userId), new Counts(dueToday, overdue, toReview)));
        }
        return result;
    }

    /**
     * Writes the row for this person and day in a transaction of its own. False when another
     * run wrote it first: the unique key refuses the second one.
     */
    boolean claim(long organisationId, long userId, LocalDate today) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                DailySummary row = new DailySummary();
                row.organisationId = organisationId;
                row.userId = userId;
                row.day = today;
                row.persist();
            });
            return true;
        } catch (RuntimeException e) {
            boolean takenByOther = QuarkusTransaction.requiringNew().call(() -> DailySummary.count(
                    "organisationId = ?1 and userId = ?2 and day = ?3", organisationId, userId, today) > 0);
            if (takenByOther) {
                return false;
            }
            throw e;
        }
    }

    private io.quarkus.mailer.MailTemplate.MailTemplateInstance mail(long organisationId, Recipient recipient) {
        Counts c = recipient.counts();
        String myTasks = mails.link(NotificationMailer.withOrganisation("/my-tasks", organisationId));
        List<Line> lines = new ArrayList<>();
        if (c.dueToday() > 0) {
            lines.add(new Line(c.dueToday() == 1 ? "1 of your tasks is due today." : c.dueToday() + " of your tasks are due today.",
                    myTasks));
        }
        if (c.overdue() > 0) {
            lines.add(new Line(c.overdue() == 1 ? "1 of your tasks is overdue." : c.overdue() + " of your tasks are overdue.",
                    myTasks));
        }
        if (c.toReview() > 0) {
            lines.add(new Line(c.toReview() == 1 ? "1 timesheet is waiting for your review."
                    : c.toReview() + " timesheets are waiting for your review.",
                    mails.link(NotificationMailer.withOrganisation("/approvals", organisationId))));
        }
        Line stop = new Line("Stop the morning summary",
                mails.link("/unsubscribe", unsubscribe.token(recipient.userId(), NotificationType.DAILY_SUMMARY)));
        return mails.dailySummary(recipient.email(), lines, stop);
    }
}
