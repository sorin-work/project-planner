package com.projectplanner.attachment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.task.Task;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Files on tasks. Everybody who can see the project sees its files and can attach one:
 * the team, and client viewers of its client, since sending the brief is what a client
 * does. A file is removed by whoever attached it, or by an owner or a manager. The bytes
 * are not served here but through a short-lived link, see {@link SignedLinks}.
 */
@Path("/api")
@Authenticated
@Tag(name = "Attachments")
public class AttachmentResource {

    private final OrgAccess access;
    private final AttachmentService attachments;
    private final SignedLinks links;

    public AttachmentResource(OrgAccess access, AttachmentService attachments, SignedLinks links) {
        this.access = access;
        this.attachments = attachments;
        this.links = links;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttachmentResponse(long id, long taskId, String fileName, String contentType, long sizeBytes, Long uploaderId,
                                     String uploaderName, Instant createdAt, boolean previewable, boolean canDelete) {
    }

    /**
     * @param url       the path of the bytes, good until {@code expiresAt}. It needs no token.
     * @param expiresAt when the link stops working
     */
    public record AttachmentLink(String url, Instant expiresAt) {
    }

    @GET
    @Path("/tasks/{taskId}/attachments")
    @Operation(summary = "List the files attached to a task", description = "For everybody who can see the project.")
    public List<AttachmentResponse> list(@PathParam("taskId") long taskId) {
        Task task = task(taskId);
        Membership me = access.viewProject(access.project(task.projectId));
        return responses(attachments.list(task), me);
    }

    @POST
    @Path("/tasks/{taskId}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Attach a file to a task",
            description = "For everybody who can see the project, client viewers of its client included. The file is sent as the form "
                    + "field \"file\". What it is is worked out from its first bytes, which have to match its name: images (PNG, JPEG, GIF, "
                    + "WebP), PDF, plain text and office documents. Nothing that a browser would run is taken, SVG and HTML included.")
    @Fails(status = 400, code = "file_required", when = "the request has no form field \"file\"")
    @Fails(status = 413, code = "file_too_large", when = "the file is larger than the limit for one file")
    @Fails(status = 415, code = "unsupported_file_type", when = "the file is not of a kind that is taken, or its first bytes do not match its name")
    @Fails(status = 413, code = "storage_full", when = "the organisation's files would take more than its limit")
    public AttachmentResponse upload(@PathParam("taskId") long taskId, @RestForm("file") FileUpload file) {
        Task task = task(taskId);
        Project project = access.project(task.projectId);
        Membership me = access.viewProject(project);
        Attachment attachment = attachments.upload(me, project, task, file == null ? null : file.uploadedFile(),
                file == null ? null : file.fileName());
        return responses(List.of(attachment), me).getFirst();
    }

    @POST
    @Path("/attachments/{attachmentId}/link")
    @Operation(summary = "Get a short-lived link to the bytes of a file",
            description = "For everybody who can see the project. The link needs no token and works for two minutes. With preview=true "
                    + "an image is shown in the page instead of downloaded; other files are always downloads.")
    public AttachmentLink link(@PathParam("attachmentId") long attachmentId, @QueryParam("preview") @DefaultValue("false") boolean preview) {
        Attachment attachment = find(attachmentId);
        access.viewProject(access.project(task(attachment.taskId).projectId));
        SignedLinks.Link link = links.issue(attachment.id, access.userId(), preview && AttachmentService.previewable(attachment));
        String url = "/api/attachments/" + attachment.id + "/content?user=" + link.userId() + "&expires=" + link.expires()
                + "&inline=" + link.inline() + "&signature=" + links.signature(link);
        return new AttachmentLink(url, Instant.ofEpochSecond(link.expires()));
    }

    @DELETE
    @Path("/attachments/{attachmentId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Remove a file from a task",
            description = "For whoever attached it, and for owners and managers. The file is deleted once the change is committed.")
    public void delete(@PathParam("attachmentId") long attachmentId) {
        Attachment attachment = find(attachmentId);
        Task task = task(attachment.taskId);
        Project project = access.project(task.projectId);
        Membership me = access.viewProject(project);
        if (!mayDelete(me, attachment)) {
            throw ApiException.forbidden("Only whoever attached a file, or an owner or a manager, can remove it");
        }
        attachments.delete(me, project, task, attachment);
    }

    private static boolean mayDelete(Membership me, Attachment attachment) {
        return Objects.equals(attachment.uploaderId, me.userId) || me.role == Role.OWNER || me.role == Role.MANAGER;
    }

    private List<AttachmentResponse> responses(List<Attachment> list, Membership me) {
        Set<Long> uploaders = list.stream().map(a -> a.uploaderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!uploaders.isEmpty()) {
            User.<User>list("id in ?1", uploaders).forEach(u -> names.put(u.id, u.name));
        }
        return list.stream().map(a -> new AttachmentResponse(a.id, a.taskId, a.fileName, a.contentType, a.sizeBytes, a.uploaderId,
                a.uploaderId == null ? null : names.get(a.uploaderId), a.createdAt, AttachmentService.previewable(a), mayDelete(me, a))).toList();
    }

    /** The attachment if its task is in a project the caller can see. Otherwise 404, whether it exists or not. */
    private Attachment find(long attachmentId) {
        Attachment attachment = Attachment.findById(attachmentId);
        if (attachment == null) {
            throw ApiException.notFound("Attachment");
        }
        try {
            access.project(task(attachment.taskId).projectId);
        } catch (ApiException e) {
            throw ApiException.notFound("Attachment");
        }
        return attachment;
    }

    private static Task task(long taskId) {
        Task task = Task.findById(taskId);
        if (task == null) {
            throw ApiException.notFound("Task");
        }
        return task;
    }
}
