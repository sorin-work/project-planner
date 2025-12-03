package com.projectplanner.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "phases")
public class Phase extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public int position;

    @Column(name = "start_date")
    public LocalDate startDate;

    @Column(name = "end_date")
    public LocalDate endDate;

    public static List<Phase> forProject(long projectId) {
        return list("projectId = ?1 order by position, id", projectId);
    }
}
