package com.projectplanner.time;

public enum TimesheetStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED;

    /** Submitted and approved weeks can't be edited until a manager sends them back. */
    public boolean locked() {
        return this == SUBMITTED || this == APPROVED;
    }
}
