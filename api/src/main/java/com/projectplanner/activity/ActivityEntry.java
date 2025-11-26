package com.projectplanner.activity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "activity_log")
public class ActivityEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "task_id")
    public Long taskId;

    @Column(name = "actor_id")
    public Long actorId;

    /** Dotted event name such as "task.created" or "timesheet.approved". */
    @Column(nullable = false)
    public String action;

    @Column(nullable = false)
    public String summary;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
