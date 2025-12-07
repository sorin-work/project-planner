package com.projectplanner.schedule;

/** A finish-to-start dependency between two tasks, with a lag in working days. */
public record Link(long predecessorId, long successorId, int lagDays) {

    public Link(long predecessorId, long successorId) {
        this(predecessorId, successorId, 0);
    }
}
