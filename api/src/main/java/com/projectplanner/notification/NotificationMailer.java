package com.projectplanner.notification;

import com.projectplanner.auth.User;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.mail.Mails;
import com.projectplanner.mail.Mails.Line;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.task.Task;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The outbox of notification mails: once a minute, every notification that still waits
 * for its mail ({@code emailedAt} null) is looked at again and the ones of one person go
 * out in one mail. It runs after the notifications were committed, so a rollback never
 * leaves a mail behind, and a slow mail server holds up nobody.
 * <p>
 * Before a notification is mailed it is checked against the state of the moment: it is
 * not mailed when it was read in the meantime, when its type is no longer mailed to the
 * person, when they left the organisation, or when they can no longer see what it is
 * about (a project of another client, a budget or approvals after losing the manager
 * role). It is then marked as done without a mail.
 * <p>
 * A mail that fails is tried again after 1, 2, 4, 8 and 16 minutes, and then given up,
 * which the log says. The job runs on every instance of the API, so the app expects one.
 */
@ApplicationScoped
public class NotificationMailer {

    private static final Logger LOG = Logger.getLogger(NotificationMailer.class);
    static final int MAX_ATTEMPTS = 6;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final int PER_MAIL = 50;
    private static final String KIND = "notification";

    private final MailSettings settings;
    private final Mails mails;
    private final MailOutbox outbox;
    private final NotificationPreferences preferences;
    private final UnsubscribeLinks unsubscribe;
    private final Clock clock;

    public NotificationMailer(MailSettings settings, Mails mails, MailOutbox outbox, NotificationPreferences preferences,
                              UnsubscribeLinks unsubscribe, Clock clock) {
        this.settings = settings;
        this.mails = mails;
        this.outbox = outbox;
        this.preferences = preferences;
        this.unsubscribe = unsubscribe;
        this.clock = clock;
    }

    @Scheduled(identity = "notification-mails", every = "60s", delayed = "30s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        run();
    }

    /** One round of the job. Answers how many mails went out. */
    public int run() {
        if (!settings.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Long> people = QuarkusTransaction.requiringNew().call(() -> Notification.getEntityManager()
                .createQuery("select distinct n.userId from Notification n where n.emailedAt is null"
                        + " and (n.emailRetryAt is null or n.emailRetryAt <= :now) order by n.userId", Long.class)
                .setParameter("now", now).getResultList());
        int sent = 0;
        for (long userId : people) {
            Optional<Batch> batch = QuarkusTransaction.requiringNew().call(() -> prepare(userId, now));
            if (batch.isEmpty()) {
                continue;
            }
            boolean delivered = outbox.deliver(KIND, batch.get().mail());
            QuarkusTransaction.requiringNew().run(() -> finish(batch.get().ids(), delivered, now));
            if (delivered) {
                sent++;
            }
        }
        return sent;
    }

    private record Batch(List<Long> ids, MailTemplateInstance mail) {
    }

    /** Marks what needs no mail and puts the rest in one mail, without sending it. */
    private Optional<Batch> prepare(long userId, Instant now) {
        List<Notification> due = Notification.<Notification>find("userId = ?1 and emailedAt is null"
                + " and (emailRetryAt is null or emailRetryAt <= ?2) order by id", userId, now).page(0, PER_MAIL).list();
        User user = User.findById(userId);
        Map<NotificationType, Delivery> wanted = preferences.of(userId);
        List<Notification> mailed = new ArrayList<>();
        for (Notification n : due) {
            if (user != null && n.readAt == null && wanted.get(n.type) == Delivery.EMAIL && stillVisible(n)) {
                mailed.add(n);
            } else {
                n.emailedAt = now;
            }
        }
        if (mailed.isEmpty()) {
            return Optional.empty();
        }
        List<Line> lines = mailed.stream().map(n -> new Line(sentence(n), mails.link(target(n)))).toList();
        Set<NotificationType> types = new LinkedHashSet<>();
        mailed.forEach(n -> types.add(n.type));
        List<Line> stop = types.stream()
                .map(type -> new Line("Stop mails about " + about(type), mails.link("/unsubscribe", unsubscribe.token(userId, type))))
                .toList();
        return Optional.of(new Batch(mailed.stream().map(n -> n.id).toList(), mails.notifications(user.email, lines, stop)));
    }

    private void finish(List<Long> ids, boolean delivered, Instant now) {
        if (delivered) {
            Notification.update("emailedAt = ?1 where id in ?2", now, ids);
            return;
        }
        for (Notification n : Notification.<Notification>list("id in ?1", ids)) {
            n.emailAttempts++;
            if (n.emailAttempts >= MAX_ATTEMPTS) {
                n.emailedAt = now;
                LOG.warnf("Gave up mailing notification %d after %d attempts", n.id, n.emailAttempts);
            } else {
                n.emailRetryAt = now.plus(FIRST_RETRY.multipliedBy(1L << (n.emailAttempts - 1)));
            }
        }
    }

    /** Whether the person may still see what the notification is about. */
    static boolean stillVisible(Notification n) {
        Optional<Membership> found = Membership.find(n.organisationId, n.userId);
        if (found.isEmpty()) {
            return false;
        }
        Membership membership = found.get();
        if ((n.type == NotificationType.BUDGET_ALERT || n.type == NotificationType.TIMESHEET_SUBMITTED)
                && !membership.role.canManage()) {
            return false;
        }
        if (n.projectId != null) {
            Project project = Project.findById(n.projectId);
            if (project == null || !OrgAccess.canSee(membership, project)) {
                return false;
            }
        }
        return n.taskId == null || Task.findById(n.taskId) != null;
    }

    /** The fixed sentence for a notification. Nothing in it was typed by a user. */
    static String sentence(Notification n) {
        String week = n.weekStart == null ? "" : " for the week of " + n.weekStart;
        return switch (n.type) {
            case MENTIONED -> "You were mentioned in a comment.";
            case ASSIGNED -> "A task was assigned to you.";
            case COMMENTED -> "There is a new comment on a task assigned to you.";
            case TIMESHEET_SUBMITTED -> "A timesheet" + week + " is waiting for your approval.";
            case TIMESHEET_APPROVED -> "Your timesheet" + week + " was approved.";
            case TIMESHEET_REJECTED -> "Your timesheet" + week + " was sent back.";
            case TIMESHEET_REMINDER -> "You were reminded to submit your timesheet" + week + ".";
            case BUDGET_ALERT -> "A project passed a budget threshold.";
        };
    }

    static String about(NotificationType type) {
        return switch (type) {
            case MENTIONED -> "mentions";
            case ASSIGNED -> "tasks assigned to you";
            case COMMENTED -> "comments on your tasks";
            case TIMESHEET_SUBMITTED -> "timesheets waiting for approval";
            case TIMESHEET_APPROVED -> "approved timesheets";
            case TIMESHEET_REJECTED -> "timesheets sent back";
            case TIMESHEET_REMINDER -> "timesheet reminders";
            case BUDGET_ALERT -> "budget alerts";
        };
    }

    /** Where the web app opens the notification, as in notificationTarget on the web side. */
    static String target(Notification n) {
        if (n.projectId != null && n.taskId != null) {
            return "/projects/" + n.projectId + "/list?task=" + n.taskId;
        }
        if (n.projectId != null) {
            return "/projects/" + n.projectId + "/overview";
        }
        if (n.type == NotificationType.TIMESHEET_SUBMITTED) {
            return "/approvals";
        }
        if (n.weekStart != null) {
            return "/time?week=" + n.weekStart;
        }
        return n.type.name().startsWith("TIMESHEET_") ? "/time" : "/notifications";
    }
}
