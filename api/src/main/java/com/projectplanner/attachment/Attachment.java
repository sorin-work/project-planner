package com.projectplanner.attachment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** A file attached to a task. The bytes are in the {@link AttachmentStore} under {@code storageKey}. */
@Entity
@Table(name = "attachments")
public class Attachment extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "uploader_id")
    public Long uploaderId;

    @Column(name = "file_name", nullable = false)
    public String fileName;

    @Column(name = "content_type", nullable = false)
    public String contentType;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "storage_key", nullable = false)
    public String storageKey;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** The storage keys of the files of these tasks. */
    public static List<String> keysOfTasks(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return getEntityManager().createQuery("select a.storageKey from Attachment a where a.taskId in ?1", String.class)
                .setParameter(1, taskIds).getResultList();
    }

    /** The storage keys of the files of every task of a project. */
    public static List<String> keysOfProject(long projectId) {
        return getEntityManager().createQuery(
                        "select a.storageKey from Attachment a, com.projectplanner.task.Task t where t.id = a.taskId and t.projectId = ?1", String.class)
                .setParameter(1, projectId).getResultList();
    }

    /** Bytes the organisation's files take, for its limit. */
    public static long bytesOf(long organisationId) {
        Long sum = getEntityManager().createQuery("select coalesce(sum(a.sizeBytes), 0) from Attachment a where a.organisationId = ?1", Long.class)
                .setParameter(1, organisationId).getSingleResult();
        return sum == null ? 0 : sum;
    }
}
