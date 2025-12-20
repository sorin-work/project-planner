package com.projectplanner.comment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "comments")
public class Comment extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "author_id", nullable = false)
    public Long authorId;

    @Column(nullable = false)
    public String body;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "edited_at")
    public Instant editedAt;
}
