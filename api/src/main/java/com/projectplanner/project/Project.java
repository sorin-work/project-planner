package com.projectplanner.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "projects")
public class Project extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "client_id")
    public Long clientId;

    @Column(nullable = false)
    public String name;

    /** Short reference like "ACME-WEB", unique within the organisation. */
    @Column(nullable = false)
    public String code;

    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ProjectStatus status = ProjectStatus.PLANNED;

    @Column(name = "start_date")
    public LocalDate startDate;

    @Column(name = "end_date")
    public LocalDate endDate;

    @Column(name = "budget_hours")
    public BigDecimal budgetHours;

    @Column(name = "budget_amount")
    public BigDecimal budgetAmount;

    /** Fixed rate for this project; overrides member and role rates when set. */
    @Column(name = "hourly_rate")
    public BigDecimal hourlyRate;

    @Column(nullable = false)
    public String color = "#4f6bed";

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** The highest threshold of the hours budget that has been announced: 0, 80 or 100. */
    @Column(name = "hours_alert_pct", nullable = false)
    public int hoursAlertPct;

    /** The same for the money budget. The two are kept apart so that one does not hide the other. */
    @Column(name = "amount_alert_pct", nullable = false)
    public int amountAlertPct;

    /**
     * The project with its row locked until the transaction ends. Whatever changes the time
     * or the budget of a project takes this first, so that two changes at the same moment
     * happen one after the other and the second one sees what the first one left: the burn,
     * and the alert that may already have gone out for it.
     */
    public static Project locked(long id) {
        Project project = findById(id);
        if (project != null) {
            // Read again under the lock: a copy that was read before it may hold levels
            // that another change has moved since.
            getEntityManager().refresh(project, LockModeType.PESSIMISTIC_WRITE);
        }
        return project;
    }
}
