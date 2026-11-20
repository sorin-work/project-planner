package com.projectplanner.baseline;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * A phase as it was when a baseline was taken. Written once, like {@link BaselineTask},
 * and {@code phaseId} is a plain value for the same reason.
 */
@Entity
@Table(name = "baseline_phases")
public class BaselinePhase extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "baseline_id", nullable = false)
    public Long baselineId;

    @Column(name = "phase_id", nullable = false)
    public Long phaseId;

    @Column(nullable = false)
    public String name;

    @Column(name = "start_date")
    public LocalDate startDate;

    @Column(name = "end_date")
    public LocalDate endDate;
}
