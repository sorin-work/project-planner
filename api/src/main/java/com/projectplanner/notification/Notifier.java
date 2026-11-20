package com.projectplanner.notification;

import com.projectplanner.mail.MailSettings;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

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
        create(userId, organisationId, actorId, type, message, projectId, taskId, null);
    }

    /** A timesheet notification: it has no project or task, but says which week it is about. */
    public void notifyAboutWeek(long userId, long organisationId, Long actorId, NotificationType type, String message,
                                LocalDate weekStart) {
        create(userId, organisationId, actorId, type, message, null, null, weekStart);
    }

    private void create(long userId, long organisationId, Long actorId, NotificationType type, String message,
                        Long projectId, Long taskId, LocalDate weekStart) {
        if (Objects.equals(userId, actorId)) {
            return;
        }
        Delivery delivery = preferences.of(userId, type);
        if (delivery == Delivery.OFF) {
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
