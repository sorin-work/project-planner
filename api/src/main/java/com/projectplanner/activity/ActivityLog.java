package com.projectplanner.activity;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;

/** Appends to the activity log. Call it inside the transaction that made the change. */
@ApplicationScoped
public class ActivityLog {


    private final Clock clock;

    public ActivityLog(Clock clock) {
        this.clock = clock;
    }

    public ActivityEntry record(long organisationId, Long projectId, Long taskId, Long actorId, String action, String summary) {
        ActivityEntry entry = new ActivityEntry();
        entry.organisationId = organisationId;
        entry.projectId = projectId;
        entry.taskId = taskId;
        entry.actorId = actorId;
        entry.action = action;
        entry.summary = summary;
        entry.createdAt = Instant.now(clock);
        entry.persist();
        return entry;
    }
}
