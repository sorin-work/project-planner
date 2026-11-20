package com.projectplanner.schedule;

/**
 * A dependency between two tasks: its type and a lag in working days. A negative
 * lag is a lead: finish-to-start with -2 lets the successor start two working days
 * before the predecessor ends.
 */
public record Link(long predecessorId, long successorId, int lagDays, DependencyType type) {

    public Link {
        if (type == null) {
            type = DependencyType.FS;
        }
    }

    public Link(long predecessorId, long successorId, int lagDays) {
        this(predecessorId, successorId, lagDays, DependencyType.FS);
    }

    public Link(long predecessorId, long successorId) {
        this(predecessorId, successorId, 0, DependencyType.FS);
    }
}
