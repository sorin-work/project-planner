package com.projectplanner.attachment;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.Project;
import com.projectplanner.task.Task;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class AttachmentService {

    private static final Logger LOG = Logger.getLogger(AttachmentService.class);

    /** A file without a row is left alone this long, so that an upload in progress is never swept. */
    static final Duration SWEEP_GRACE = Duration.ofHours(1);

    private static final int MAX_NAME = 255;

    private final AttachmentStore store;
    private final AttachmentFiles files;
    private final ActivityLog activity;
    private final Clock clock;
    private final long maxFileBytes;
    private final long maxOrganisationBytes;

    public AttachmentService(AttachmentStore store, AttachmentFiles files, ActivityLog activity, Clock clock,
                             @ConfigProperty(name = "planner.attachments.max-file-bytes") long maxFileBytes,
                             @ConfigProperty(name = "planner.attachments.max-organisation-bytes") long maxOrganisationBytes) {
        this.store = store;
        this.files = files;
        this.activity = activity;
        this.clock = clock;
        this.maxFileBytes = maxFileBytes;
        this.maxOrganisationBytes = maxOrganisationBytes;
    }

    public List<Attachment> list(Task task) {
        return Attachment.list("taskId = ?1 order by createdAt, id", task.id);
    }

    /**
     * Stores an uploaded file for {@code task}. The checks come first, in this order:
     * that there is a file, its size, what it is, and the organisation's space. Then the
     * file is copied into the store, and it goes again if the row is not committed.
     *
     * @param upload where the upload is on disk now, null when the request had no file
     */
    public Attachment upload(Membership me, Project project, Task task, Path upload, String uploadedName) {
        if (upload == null) {
            throw ApiException.badRequest("file_required", "Send the file as the form field \"file\".");
        }
        long size = size(upload);
        if (size > maxFileBytes) {
            throw new ApiException(413, "file_too_large", "A file can be up to " + megabytes(maxFileBytes) + ".");
        }
        String name = cleanName(uploadedName);
        FileTypes.FileType type = detect(name, upload, size);
        if (type == null) {
            throw new ApiException(415, "unsupported_file_type",
                    "This kind of file cannot be attached, or it is not what its name says. These can: " + FileTypes.allowed() + ".");
        }
        // Several uploads at once in different projects can each pass this; the limit can be passed by those files.
        long used = Attachment.bytesOf(project.organisationId);
        if (used + size > maxOrganisationBytes) {
            throw new ApiException(413, "storage_full", "The organisation's attachments take " + megabytes(used) + " of "
                    + megabytes(maxOrganisationBytes) + ", and this file does not fit. Delete some to make room.");
        }

        String key = store.save(upload);
        files.deleteUnlessCommitted(List.of(key));
        Attachment attachment = new Attachment();
        attachment.organisationId = project.organisationId;
        attachment.taskId = task.id;
        attachment.uploaderId = me.userId;
        attachment.fileName = name;
        attachment.contentType = type.contentType();
        attachment.sizeBytes = size;
        attachment.storageKey = key;
        attachment.createdAt = Instant.now(clock);
        attachment.persist();
        activity.record(project.organisationId, project.id, task.id, me.userId, "attachment.added",
                "Attached " + name + " to " + task.title);
        return attachment;
    }

    public void delete(Membership me, Project project, Task task, Attachment attachment) {
        attachment.delete();
        files.deleteAfterCommit(List.of(attachment.storageKey));
        activity.record(project.organisationId, project.id, task.id, me.userId, "attachment.deleted",
                "Removed " + attachment.fileName + " from " + task.title);
    }

    /** Whether the file can be shown in the page: one of the raster image formats. */
    public static boolean previewable(Attachment attachment) {
        return attachment.contentType.startsWith("image/");
    }

    /** Removes the files that no attachment row knows, every six hours. */
    @Scheduled(every = "6h", delayed = "10m", identity = "attachment-sweep")
    @Transactional
    public void sweep() {
        Set<String> known = new HashSet<>(Attachment.getEntityManager()
                .createQuery("select a.storageKey from Attachment a", String.class).getResultList());
        int removed = store.sweep(known, Instant.now(clock).minus(SWEEP_GRACE));
        if (removed > 0) {
            LOG.infof("Removed %d attachment files without a row", removed);
        }
    }

    /** The name a file is shown and downloaded under: no folders, no control characters, not too long. */
    static String cleanName(String uploaded) {
        String name = uploaded == null ? "" : uploaded;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        if (name.length() > MAX_NAME) {
            String extension = FileTypes.extension(name);
            int keep = MAX_NAME - extension.length() - 1;
            name = extension.isEmpty() || keep < 1 ? name.substring(0, MAX_NAME) : name.substring(0, keep) + "." + extension;
        }
        return name;
    }

    private static FileTypes.FileType detect(String name, Path upload, long size) {
        try (InputStream in = Files.newInputStream(upload)) {
            byte[] head = in.readNBytes(FileTypes.HEAD_BYTES);
            return FileTypes.detect(name, head, head.length >= size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path upload) {
        try {
            return Files.size(upload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
