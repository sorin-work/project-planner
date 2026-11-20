package com.projectplanner.notification;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Creates in-app notifications. People are never notified about their own actions. */
@ApplicationScoped
public class Notifier {

    private final Clock clock;

    public Notifier(Clock clock) {
        this.clock = clock;
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
        notification.persist();
    }
}
