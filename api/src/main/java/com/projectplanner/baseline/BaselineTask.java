package com.projectplanner.baseline;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A task as it was when a baseline was taken. Written once, by the statement that
 * takes the baseline, and only read after that. {@code taskId} is a plain value:
 * the row stays when the task is deleted, which is how a comparison knows that a
 * task has been removed.
 */
@Entity
@Table(name = "baseline_tasks")
public class BaselineTask extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "baseline_id", nullable = false)
    public Long baselineId;

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "parent_task_id")
    public Long parentTaskId;

    @Column(name = "phase_id")
    public Long phaseId;

    @Column(name = "phase_name")
    public String phaseName;

    @Column(nullable = false)
    public String title;

    @Column(name = "start_date")
    public LocalDate startDate;

    @Column(name = "due_date")
    public LocalDate dueDate;

    @Column(name = "estimate_hours")
    public BigDecimal estimateHours;

    @Column(nullable = false)
    public boolean milestone;
}
