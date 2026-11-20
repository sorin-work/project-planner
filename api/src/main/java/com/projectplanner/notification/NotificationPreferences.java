package com.projectplanner.notification;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumMap;
import java.util.Map;

/**
 * Which types somebody gets, and how. Mail by default for what asks something of the
 * person (a mention, a task, a timesheet sent back or a reminder), the app only for the
 * rest, which is news rather than a request.
 */
@ApplicationScoped
public class NotificationPreferences {

    public static final Map<NotificationType, Delivery> DEFAULTS = defaults();

    private static Map<NotificationType, Delivery> defaults() {
        Map<NotificationType, Delivery> map = new EnumMap<>(NotificationType.class);
        for (NotificationType type : NotificationType.values()) {
            map.put(type, Delivery.IN_APP);
        }
        map.put(NotificationType.MENTIONED, Delivery.EMAIL);
        map.put(NotificationType.ASSIGNED, Delivery.EMAIL);
        map.put(NotificationType.TIMESHEET_REJECTED, Delivery.EMAIL);
        map.put(NotificationType.TIMESHEET_REMINDER, Delivery.EMAIL);
        return Map.copyOf(map);
    }

    /** Every type with the delivery that holds for the user. */
    public Map<NotificationType, Delivery> of(long userId) {
        Map<NotificationType, Delivery> map = new EnumMap<>(DEFAULTS);
        NotificationPreference.<NotificationPreference>list("userId", userId).forEach(p -> map.put(p.type, p.delivery));
        return map;
    }

    public Delivery of(long userId, NotificationType type) {
        NotificationPreference stored = NotificationPreference.find("userId = ?1 and type = ?2", userId, type).firstResult();
        return stored == null ? DEFAULTS.get(type) : stored.delivery;
    }

    /** Stores a choice. One that is the default is stored too, so that a later change of default does not move it. */
    public void set(long userId, NotificationType type, Delivery delivery) {
        NotificationPreference stored = NotificationPreference.find("userId = ?1 and type = ?2", userId, type).firstResult();
        if (stored == null) {
            stored = new NotificationPreference();
            stored.userId = userId;
            stored.type = type;
        }
        stored.delivery = delivery;
        stored.persist();
    }
}
