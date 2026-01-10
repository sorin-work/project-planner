package com.projectplanner.time;

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
import java.util.Optional;

/** One person's week of time (Monday to Sunday) and where it is in the approval flow. */
@Entity
@Table(name = "timesheets")
public class Timesheet extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "week_start", nullable = false)
    public LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TimesheetStatus status = TimesheetStatus.DRAFT;

    @Column(name = "submitted_at")
    public Instant submittedAt;

    @Column(name = "reviewed_at")
    public Instant reviewedAt;

    @Column(name = "reviewed_by")
    public Long reviewedBy;

    @Column(name = "review_note")
    public String reviewNote;

    public static Optional<Timesheet> find(long organisationId, long userId, LocalDate weekStart) {
        return find("organisationId = ?1 and userId = ?2 and weekStart = ?3", organisationId, userId, weekStart)
                .firstResultOptional();
    }

    public static TimesheetStatus statusOf(long organisationId, long userId, LocalDate weekStart) {
        return find(organisationId, userId, weekStart).map(t -> t.status).orElse(TimesheetStatus.DRAFT);
    }
}
