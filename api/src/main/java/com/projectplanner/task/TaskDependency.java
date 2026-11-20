package com.projectplanner.task;

import com.projectplanner.schedule.DependencyType;
import com.projectplanner.schedule.Link;
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
import java.util.List;

@Entity
@Table(name = "task_dependencies")
public class TaskDependency extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "predecessor_id", nullable = false)
    public Long predecessorId;

    @Column(name = "successor_id", nullable = false)
    public Long successorId;

    /** Working days, negative for a lead. */
    @Column(name = "lag_days", nullable = false)
    public int lagDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependency_type", nullable = false, length = 2)
    public DependencyType type = DependencyType.FS;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public Link toLink() {
        return new Link(predecessorId, successorId, lagDays, type);
    }

    /** All dependencies between tasks of a project. Both ends are always in the same project. */
    public static List<TaskDependency> forProject(long projectId) {
        return find("select d from TaskDependency d, Task t where t.id = d.successorId and t.projectId = ?1 order by d.id", projectId).list();
    }
}
