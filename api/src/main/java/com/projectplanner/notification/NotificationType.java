package com.projectplanner.notification;

public enum NotificationType {
    MENTIONED,
    ASSIGNED,
    COMMENTED,
    TIMESHEET_SUBMITTED,
    TIMESHEET_APPROVED,
    TIMESHEET_REJECTED,
    TIMESHEET_REMINDER,
    BUDGET_ALERT,
    /**
     * The morning summary. Mail only: there is never a notification of this type, only a
     * choice of EMAIL (on) or OFF, which defaults to on for owners and managers.
     */
    DAILY_SUMMARY;

    /** A kind that is only ever a mail, never a notification in the app. */
    public boolean mailOnly() {
        return this == DAILY_SUMMARY;
    }
}
