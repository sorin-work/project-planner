package com.projectplanner.baseline;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A saved copy of a plan's dates. Only its name and whether it is the project's
 * reference can change afterwards. The dates never do.
 */
@Entity
@Table(name = "baselines")
public class Baseline extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(nullable = false)
    public String name;

    /** The baseline of its project that comparisons use unless another is picked. At most one per project. */
    @Column(name = "is_reference", nullable = false)
    public boolean reference;

    /** The project's own dates when the baseline was taken. */
    @Column(name = "project_start")
    public LocalDate projectStart;

    @Column(name = "project_end")
    public LocalDate projectEnd;

    @Column(name = "created_by")
    public Long createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Oldest first: the order they were taken in. */
    public static List<Baseline> forProject(long projectId) {
        return list("projectId = ?1 order by createdAt, id", projectId);
    }
}
