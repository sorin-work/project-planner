package com.projectplanner.notification;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
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
        // On for owners and managers, see summaryDefault; this is everybody else's.
        map.put(NotificationType.DAILY_SUMMARY, Delivery.OFF);
        return Map.copyOf(map);
    }

    /** Whether the morning summary is on without a choice of the person's: for those who manage. */
    public static Delivery summaryDefault(boolean manages) {
        return manages ? Delivery.EMAIL : Delivery.OFF;
    }

    /**
     * Who of {@code userIds} gets the morning summary, by user id: their choice, or the default
     * for their role there. Anything but EMAIL is off. One query for all of them.
     */
    public Map<Long, Boolean> summaryWanted(Map<Long, Boolean> managesByUser) {
        Map<Long, Boolean> wanted = new HashMap<>();
        managesByUser.forEach((id, manages) -> wanted.put(id, summaryDefault(manages) == Delivery.EMAIL));
        if (!managesByUser.isEmpty()) {
            NotificationPreference.<NotificationPreference>list("userId in ?1 and type = ?2", managesByUser.keySet(),
                            NotificationType.DAILY_SUMMARY)
                    .forEach(p -> wanted.put(p.userId, p.delivery == Delivery.EMAIL));
        }
        return wanted;
    }

    /** Every type with the delivery that holds for the user. */
    public Map<NotificationType, Delivery> of(long userId) {
        Map<NotificationType, Delivery> map = new EnumMap<>(DEFAULTS);
        NotificationPreference.<NotificationPreference>list("userId", userId).forEach(p -> map.put(p.type, p.delivery));
        return map;
    }

    /** The choices of several people in one query, for a request that notifies several at once. */
    public Map<Long, Map<NotificationType, Delivery>> of(Collection<Long> userIds) {
        Map<Long, Map<NotificationType, Delivery>> all = new HashMap<>();
        userIds.forEach(id -> all.put(id, new EnumMap<>(DEFAULTS)));
        if (!userIds.isEmpty()) {
            NotificationPreference.<NotificationPreference>list("userId in ?1", userIds)
                    .forEach(p -> all.get(p.userId).put(p.type, p.delivery));
        }
        return all;
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
