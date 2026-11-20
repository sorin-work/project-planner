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
import jakarta.persistence.LockModeType;
import org.hibernate.LockOptions;
import org.hibernate.jpa.AvailableHints;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
 * which the log says. Several instances of the API can run the job at once: each run claims
 * the rows it takes on (see {@link #claim}), and a run that dies before it has stamped its
 * mail leaves rows that are taken on again once their claim has run out. A mail that the
 * server got but whose answer was lost in a timeout is sent again that way.
 */
@ApplicationScoped
public class NotificationMailer {

    private static final Logger LOG = Logger.getLogger(NotificationMailer.class);
    static final int MAX_ATTEMPTS = 6;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final int PER_MAIL = 50;
    static final int PER_ROUND = 500;
    /** How long a run holds the notifications it took on before another run may take them. */
    static final Duration CLAIM = Duration.ofMinutes(5);
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
        List<Batch> batches = QuarkusTransaction.requiringNew().call(() -> claim(now));
        int sent = 0;
        for (Batch batch : batches) {
            boolean delivered = outbox.deliver(KIND, batch.mail());
            QuarkusTransaction.requiringNew().run(() -> finish(batch, delivered, clock.instant()));
            if (delivered) {
                sent++;
            }
        }
        return sent;
    }

    /** What one person gets in one mail, and the attempts each of its notifications had before. */
    private record Batch(Map<Integer, List<Long>> idsByAttempts, MailTemplateInstance mail) {

        List<Long> ids() {
            return idsByAttempts.values().stream().flatMap(List::stream).toList();
        }
    }

    /**
     * Takes on what waits, in one transaction: the rows are read with {@code FOR UPDATE SKIP
     * LOCKED}, so that two runs at the same moment never take the same one, and each is
     * either marked done without a mail or claimed for {@link #CLAIM} before the transaction
     * ends. The mails are built here and sent after the commit.
     */
    private List<Batch> claim(Instant now) {
        List<Notification> due = Notification.<Notification>find("emailedAt is null"
                        + " and (emailRetryAt is null or emailRetryAt <= ?1)"
                        + " and (emailClaimedUntil is null or emailClaimedUntil <= ?1) order by userId, id", now)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint(AvailableHints.HINT_SPEC_LOCK_TIMEOUT, LockOptions.SKIP_LOCKED)
                .page(0, PER_ROUND).list();
        Map<Long, List<Notification>> byPerson = new LinkedHashMap<>();
        due.forEach(n -> byPerson.computeIfAbsent(n.userId, id -> new ArrayList<>()).add(n));

        List<Long> skipped = new ArrayList<>();
        List<Long> claimed = new ArrayList<>();
        List<Batch> batches = new ArrayList<>();
        Map<Long, Map<NotificationType, Delivery>> wanted = preferences.of(byPerson.keySet());
        for (Map.Entry<Long, List<Notification>> person : byPerson.entrySet()) {
            User user = User.findById(person.getKey());
            List<Notification> mailed = new ArrayList<>();
            for (Notification n : person.getValue()) {
                if (mailed.size() == PER_MAIL) {
                    break;
                }
                if (user != null && n.readAt == null && wanted.get(n.userId).get(n.type) == Delivery.EMAIL && stillVisible(n)) {
                    mailed.add(n);
                } else {
                    skipped.add(n.id);
                }
            }
            if (!mailed.isEmpty()) {
                mailed.forEach(n -> claimed.add(n.id));
                batches.add(batch(user, mailed));
            }
        }
        // Only the mail columns, so that a "mark all read" at the same moment is not undone.
        if (!skipped.isEmpty()) {
            Notification.update("emailedAt = ?1, emailClaimedUntil = null where id in ?2", now, skipped);
        }
        if (!claimed.isEmpty()) {
            Notification.update("emailClaimedUntil = ?1 where id in ?2", now.plus(CLAIM), claimed);
        }
        return batches;
    }

    private Batch batch(User user, List<Notification> mailed) {
        List<Line> lines = mailed.stream().map(n -> new Line(sentence(n), mails.link(target(n)))).toList();
        Set<NotificationType> types = new LinkedHashSet<>();
        mailed.forEach(n -> types.add(n.type));
        List<Line> stop = types.stream()
                .map(type -> new Line("Stop mails about " + about(type), mails.link("/unsubscribe", unsubscribe.token(user.id, type))))
                .toList();
        Map<Integer, List<Long>> idsByAttempts = new TreeMap<>();
        mailed.forEach(n -> idsByAttempts.computeIfAbsent(n.emailAttempts, a -> new ArrayList<>()).add(n.id));
        return new Batch(idsByAttempts, mails.notifications(user.email, lines, stop));
    }

    /** Stamps the outcome with bulk updates of the mail columns only. */
    private void finish(Batch batch, boolean delivered, Instant now) {
        if (delivered) {
            Notification.update("emailedAt = ?1, emailClaimedUntil = null where id in ?2", now, batch.ids());
            return;
        }
        batch.idsByAttempts().forEach((before, ids) -> {
            int attempts = before + 1;
            if (attempts >= MAX_ATTEMPTS) {
                Notification.update("emailAttempts = ?1, emailedAt = ?2, emailClaimedUntil = null where id in ?3",
                        attempts, now, ids);
                LOG.warnf("Gave up mailing %d notifications after %d attempts", ids.size(), attempts);
            } else {
                Notification.update("emailAttempts = ?1, emailRetryAt = ?2, emailClaimedUntil = null where id in ?3",
                        attempts, now.plus(FIRST_RETRY.multipliedBy(1L << (attempts - 1))), ids);
            }
        });
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
