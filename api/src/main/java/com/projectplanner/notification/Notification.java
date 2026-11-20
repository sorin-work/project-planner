package com.projectplanner.notification;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "notifications")
public class Notification extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "actor_id")
    public Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public NotificationType type;

    @Column(nullable = false)
    public String message;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "task_id")
    public Long taskId;

    /** Monday of the week a timesheet notification is about. */
    @Column(name = "week_start")
    public LocalDate weekStart;

    @Column(name = "read_at")
    public Instant readAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** When the mail outbox was done with it: mailed, or found to need no mail. Null while it waits. */
    @Column(name = "emailed_at")
    public Instant emailedAt;

    @Column(name = "email_attempts", nullable = false)
    public int emailAttempts;

    /** After a failed attempt, the earliest time of the next one. */
    @Column(name = "email_retry_at")
    public Instant emailRetryAt;

    /** A run of the mail job has taken it on until then. */
    @Column(name = "email_claimed_until")
    public Instant emailClaimedUntil;
}
