package com.projectplanner.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
}
