package com.projectplanner.task;

import com.projectplanner.schedule.ScheduledTask;
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
import java.util.List;

@Entity
@Table(name = "tasks")
public class Task extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "phase_id")
    public Long phaseId;

    /** Set for subtasks. Subtasks can't have subtasks of their own. */
    @Column(name = "parent_id")
    public Long parentId;

    @Column(nullable = false)
    public String title;

    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TaskStatus status = TaskStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Priority priority = Priority.MEDIUM;

    @Column(name = "estimate_hours")
    public BigDecimal estimateHours;

    @Column(name = "start_date")
    public LocalDate startDate;

    @Column(name = "due_date")
    public LocalDate dueDate;

    @Column(name = "assignee_id")
    public Long assigneeId;

    /** Milestones are zero-length markers: their start and due date are the same day. */
    @Column(nullable = false)
    public boolean milestone;

    @Column(nullable = false)
    public int position;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public boolean isDone() {
        return status == TaskStatus.DONE;
    }

    public double estimate() {
        return estimateHours == null ? 0 : estimateHours.doubleValue();
    }

    public ScheduledTask toScheduled() {
        return new ScheduledTask(id, startDate, dueDate);
    }

    public static List<Task> forProject(long projectId) {
        return list("projectId = ?1 order by position, id", projectId);
    }
}
