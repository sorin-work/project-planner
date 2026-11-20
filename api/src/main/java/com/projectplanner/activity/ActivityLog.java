package com.projectplanner.activity;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;

/** Appends to the activity log. Call it inside the transaction that made the change. */
@ApplicationScoped
public class ActivityLog {

    private static final int MAX_SUMMARY = 500;

    private final Clock clock;

    public ActivityLog(Clock clock) {
        this.clock = clock;
    }

    public ActivityEntry record(long organisationId, Long projectId, Long taskId, Long actorId, String action, String summary) {
        return record(organisationId, projectId, taskId, actorId, action, summary, null);
    }

    /**
     * An entry that is also about another project. Keep that project's name out of
     * {@code summary}: a reader who may not see the project is only told that there is one.
     */
    public ActivityEntry record(long organisationId, Long projectId, Long taskId, Long actorId, String action, String summary,
                                Long relatedProjectId) {
        ActivityEntry entry = new ActivityEntry();
        entry.relatedProjectId = relatedProjectId;
        entry.organisationId = organisationId;
        entry.projectId = projectId;
        entry.taskId = taskId;
        entry.actorId = actorId;
        entry.action = action;
        entry.summary = summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY - 1) + "…" : summary;
        entry.createdAt = Instant.now(clock);
        entry.persist();
        return entry;
    }
}
