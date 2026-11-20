package com.projectplanner.notification;

/** How somebody gets one type of notification. */
public enum Delivery {
    /** In the app, and by mail when the server sends mail. */
    EMAIL,
    /** In the app only. */
    IN_APP,
    /** Not at all. */
    OFF
}
