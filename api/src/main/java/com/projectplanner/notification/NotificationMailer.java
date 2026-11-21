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
import io.quarkus.panache.common.Parameters;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

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
 * one person's rows just before their mail (see {@link #claimNext}), and a run that dies
 * before it has stamped its mail leaves rows that are taken on again once their claim has
 * run out. A mail that the server got but whose answer was lost in a timeout is sent again
 * that way.
 */
@ApplicationScoped
public class NotificationMailer {

    private static final Logger LOG = Logger.getLogger(NotificationMailer.class);
    static final int MAX_ATTEMPTS = 6;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final int PER_MAIL = 50;
    /** How long a run holds one person's notifications: longer than one mail may take. */
    static final Duration CLAIM = Duration.ofMinutes(5);
    /** How long one round may go on before it leaves the rest to the next. */
    static final Duration ROUND_BUDGET = Duration.ofMinutes(4);
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

    /**
     * One round of the job. Answers how many mails went out. People are taken one at a time,
     * the one whose oldest waiting notification is oldest first, and each at most once a
     * round, so that one person's backlog does not hold up everybody else. Each person's
     * notifications are claimed just before their mail is sent. The round stops when nobody
     * is left or when it has used up {@link #ROUND_BUDGET}; what is left waits for the next.
     */
    public int run() {
        if (!settings.enabled()) {
            return 0;
        }
        Instant deadline = clock.instant().plus(ROUND_BUDGET);
        Set<Long> done = new HashSet<>();
        int sent = 0;
        while (clock.instant().isBefore(deadline)) {
            Optional<Claimed> claimed = QuarkusTransaction.requiringNew().call(() -> claimNext(clock.instant(), done));
            if (claimed.isEmpty()) {
                break;
            }
            done.add(claimed.get().userId());
            Batch batch = claimed.get().batch();
            if (batch == null) {
                continue;
            }
            boolean delivered = outbox.deliver(KIND, batch.mail());
            QuarkusTransaction.requiringNew().run(() -> finish(batch, delivered, clock.instant()));
            if (delivered) {
                sent++;
            }
        }
        return sent;
    }

    /** What one person gets in one mail, under which claim, and the attempts each notification had before. */
    private record Batch(String token, Map<Integer, List<Long>> idsByAttempts, MailTemplateInstance mail) {

        List<Long> ids() {
            return idsByAttempts.values().stream().flatMap(List::stream).toList();
        }
    }

    /** The person taken on, and their mail, or null when nothing of theirs needed one. */
    private record Claimed(long userId, Batch batch) {
    }

    /** For tests: runs inside the claim transaction, after the rows were picked and locked. */
    void afterPick(Runnable hook) {
        afterPick = hook;
    }

    private volatile Runnable afterPick = () -> {
    };

    private static final String DUE = "emailedAt is null and (emailRetryAt is null or emailRetryAt <= :now)"
            + " and (emailClaimedUntil is null or emailClaimedUntil <= :now)";

    /**
     * Takes on the next person, in one short transaction: their waiting rows are read with
     * {@code FOR UPDATE SKIP LOCKED}, so that two runs at the same moment never take the same
     * one; those that need no mail are marked done, and the rest are claimed with a token of
     * this run until {@link #CLAIM} from now. The mail is built here and sent after the commit.
     */
    private Optional<Claimed> claimNext(Instant now, Set<Long> done) {
        Parameters next = Parameters.with("now", now);
        String others = "";
        if (!done.isEmpty()) {
            others = " and userId not in :done";
            next.and("done", done);
        }
        Notification oldest = Notification.<Notification>find(DUE + others + " order by id", next)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint(AvailableHints.HINT_SPEC_LOCK_TIMEOUT, LockOptions.SKIP_LOCKED)
                .page(0, 1).firstResult();
        if (oldest == null) {
            return Optional.empty();
        }
        long userId = oldest.userId;
        List<Notification> due = Notification.<Notification>find("userId = :user and " + DUE + " order by id",
                        Parameters.with("user", userId).and("now", now))
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .withHint(AvailableHints.HINT_SPEC_LOCK_TIMEOUT, LockOptions.SKIP_LOCKED)
                .page(0, PER_MAIL).list();
        afterPick.run();

        User user = User.findById(userId);
        Map<NotificationType, Delivery> wanted = preferences.of(userId);
        List<Long> skipped = new ArrayList<>();
        List<Notification> mailed = new ArrayList<>();
        for (Notification n : due) {
            if (user != null && n.readAt == null && wanted.get(n.type) == Delivery.EMAIL && stillVisible(n)) {
                mailed.add(n);
            } else {
                skipped.add(n.id);
            }
        }
        // Only the mail columns, so that a "mark all read" at the same moment is not undone.
        if (!skipped.isEmpty()) {
            Notification.update("emailedAt = ?1, emailClaimedUntil = null, emailClaimToken = null where id in ?2", now, skipped);
        }
        if (mailed.isEmpty()) {
            return Optional.of(new Claimed(userId, null));
        }
        String token = UUID.randomUUID().toString();
        Notification.update("emailClaimedUntil = ?1, emailClaimToken = ?2 where id in ?3", now.plus(CLAIM), token,
                mailed.stream().map(n -> n.id).toList());
        return Optional.of(new Claimed(userId, batch(token, user, mailed)));
    }

    private Batch batch(String token, User user, List<Notification> mailed) {
        List<Line> lines = mailed.stream().map(n -> new Line(sentence(n), mails.link(targetIn(n)))).toList();
        Set<NotificationType> types = new LinkedHashSet<>();
        mailed.forEach(n -> types.add(n.type));
        List<Line> stop = types.stream()
                .map(type -> new Line("Stop mails about " + about(type), mails.link("/unsubscribe", unsubscribe.token(user.id, type))))
                .toList();
        Map<Integer, List<Long>> idsByAttempts = new TreeMap<>();
        mailed.forEach(n -> idsByAttempts.computeIfAbsent(n.emailAttempts, a -> new ArrayList<>()).add(n.id));
        return new Batch(token, idsByAttempts, mails.notifications(user.email, lines, stop));
    }

    /**
     * Stamps the outcome with bulk updates of the mail columns only, and only on rows that
     * still hold this run's claim: a row whose claim ran out and was taken on by another run
     * is that run's to stamp.
     */
    private void finish(Batch batch, boolean delivered, Instant now) {
        String mine = " and emailClaimToken = :token";
        if (delivered) {
            Notification.update("emailedAt = :now, emailClaimedUntil = null, emailClaimToken = null where id in :ids" + mine,
                    Parameters.with("now", now).and("ids", batch.ids()).and("token", batch.token()));
            return;
        }
        batch.idsByAttempts().forEach((before, ids) -> {
            int attempts = before + 1;
            Parameters where = Parameters.with("attempts", attempts).and("ids", ids).and("token", batch.token());
            if (attempts >= MAX_ATTEMPTS) {
                Notification.update("emailAttempts = :attempts, emailedAt = :now, emailClaimedUntil = null, emailClaimToken = null"
                        + " where id in :ids" + mine, where.and("now", now));
                LOG.warnf("Gave up mailing %d notifications after %d attempts", ids.size(), attempts);
            } else {
                Notification.update("emailAttempts = :attempts, emailRetryAt = :retry, emailClaimedUntil = null, emailClaimToken = null"
                        + " where id in :ids" + mine, where.and("retry", now.plus(FIRST_RETRY.multipliedBy(1L << (attempts - 1)))));
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
        if ((n.type == NotificationType.BUDGET_ALERT || n.type == NotificationType.SCHEDULE_ALERT
                || n.type == NotificationType.TIMESHEET_SUBMITTED)
                && !membership.role.canManage()) {
            return false;
        }
        if (n.type == NotificationType.BACKUP_ALERT && membership.role != com.projectplanner.org.Role.OWNER) {
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
            case SCHEDULE_ALERT -> "A project's schedule needs a look.";
            case BACKUP_ALERT -> "The backups of this server need a look.";
            case DAILY_SUMMARY -> "Your morning summary.";
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
            case SCHEDULE_ALERT -> "schedule alerts";
            case BACKUP_ALERT -> "backup alerts";
            case DAILY_SUMMARY -> "the morning summary";
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
        if (n.type == NotificationType.BACKUP_ALERT) {
            return "/settings";
        }
        if (n.weekStart != null) {
            return "/time?week=" + n.weekStart;
        }
        return n.type.name().startsWith("TIMESHEET_") ? "/time" : "/notifications";
    }

    /** {@link #target} with the organisation, so that the web app opens it there and not in the one it has selected. */
    static String targetIn(Notification n) {
        return withOrganisation(target(n), n.organisationId);
    }

    /** A page of the web app with {@code org=} added. */
    public static String withOrganisation(String page, long organisationId) {
        return page + (page.contains("?") ? "&" : "?") + "org=" + organisationId;
    }
}
