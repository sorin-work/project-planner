package com.projectplanner.notification;

import com.projectplanner.mail.MailSettings;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Creates notifications. People are never notified about their own actions, nor about a
 * type they switched off. No mail is sent from here: this runs inside the transaction of
 * the request, so a slow mail server would hold the request up and a rollback would come
 * too late to take the mail back. A notification that is to be mailed is left with no
 * {@code emailedAt}, and {@link NotificationMailer} sends it once it is committed.
 */
@ApplicationScoped
public class Notifier {

    private final Clock clock;
    private final NotificationPreferences preferences;
    private final MailSettings mail;

    public Notifier(Clock clock, NotificationPreferences preferences, MailSettings mail) {
        this.clock = clock;
        this.preferences = preferences;
        this.mail = mail;
    }

    public void notify(long userId, long organisationId, Long actorId, NotificationType type, String message,
                       Long projectId, Long taskId) {
        create(userId, organisationId, actorId, type, message, projectId, taskId, null, preferences.of(userId, type));
    }

    /**
     * Several people about one thing, each with a type of their own, in the order given. Their
     * choices are read in one query, so that a comment that mentions ten people does not ask
     * ten times.
     */
    public void notifyEach(Map<Long, NotificationType> recipients, long organisationId, Long actorId,
                           Function<NotificationType, String> message, Long projectId, Long taskId) {
        Map<Long, Map<NotificationType, Delivery>> choices = preferences.of(recipients.keySet());
        recipients.forEach((userId, type) -> create(userId, organisationId, actorId, type, message.apply(type),
                projectId, taskId, null, choices.get(userId).get(type)));
    }

    /** Several people, one type, one thing: their choices are read in one query. */
    public void notifyAll(Collection<Long> userIds, long organisationId, Long actorId, NotificationType type, String message,
                          Long projectId, Long taskId) {
        Map<Long, Map<NotificationType, Delivery>> choices = preferences.of(userIds);
        userIds.forEach(userId -> create(userId, organisationId, actorId, type, message, projectId, taskId, null,
                choices.get(userId).get(type)));
    }

    /** {@link #notifyAboutWeek} for several people, with one query for their choices. */
    public void notifyAllAboutWeek(Collection<Long> userIds, long organisationId, Long actorId, NotificationType type,
                                   String message, LocalDate weekStart) {
        Map<Long, Map<NotificationType, Delivery>> choices = preferences.of(userIds);
        userIds.forEach(userId -> create(userId, organisationId, actorId, type, message, null, null, weekStart,
                choices.get(userId).get(type)));
    }

    /** A timesheet notification: it has no project or task, but says which week it is about. */
    public void notifyAboutWeek(long userId, long organisationId, Long actorId, NotificationType type, String message,
                                LocalDate weekStart) {
        create(userId, organisationId, actorId, type, message, null, null, weekStart, preferences.of(userId, type));
    }

    private void create(long userId, long organisationId, Long actorId, NotificationType type, String message,
                        Long projectId, Long taskId, LocalDate weekStart, Delivery delivery) {
        if (Objects.equals(userId, actorId) || delivery == Delivery.OFF) {
            return;
        }
        Notification notification = new Notification();
        notification.userId = userId;
        notification.organisationId = organisationId;
        notification.actorId = actorId;
        notification.type = type;
        notification.message = message.length() > 500 ? message.substring(0, 499) + "…" : message;
        notification.projectId = projectId;
        notification.taskId = taskId;
        notification.weekStart = weekStart;
        notification.createdAt = Instant.now(clock);
        // Nothing to send: in the app only, or no mail on this server. Turning mail on
        // later does not send what came before.
        if (delivery != Delivery.EMAIL || !mail.enabled()) {
            notification.emailedAt = notification.createdAt;
        }
        notification.persist();
    }
}
