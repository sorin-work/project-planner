package com.projectplanner.time;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Time spent on a project, optionally against a task. A running timer is an
 * entry with {@code startedAt} set and no {@code endedAt} yet.
 */
@Entity
@Table(name = "time_entries")
public class TimeEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "task_id")
    public Long taskId;

    @Column(name = "entry_date", nullable = false)
    public LocalDate entryDate;

    @Column(nullable = false)
    public int minutes;

    public String description;

    @Column(nullable = false)
    public boolean billable = true;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "ended_at")
    public Instant endedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Made up by the client for a create, so that a repeat of it is recognised. Unique per person. */
    @Column(name = "client_key")
    public String clientKey;

    public boolean isRunning() {
        return startedAt != null && endedAt == null;
    }

    public static Optional<TimeEntry> madeWith(long organisationId, long userId, String clientKey) {
        return find("organisationId = ?1 and userId = ?2 and clientKey = ?3", organisationId, userId, clientKey)
                .firstResultOptional();
    }

    public static Optional<TimeEntry> runningFor(long organisationId, long userId) {
        return find("organisationId = ?1 and userId = ?2 and startedAt is not null and endedAt is null",
                organisationId, userId).firstResultOptional();
    }
}
