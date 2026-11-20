package com.projectplanner.comment;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.task.Task;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Path("/api")
@Authenticated
@Tag(name = "Collaboration")
public class CommentResource {

    private final OrgAccess access;
    private final Notifier notifier;
    private final ActivityLog activity;
    private final Clock clock;

    public CommentResource(OrgAccess access, Notifier notifier, ActivityLog activity, Clock clock) {
        this.access = access;
        this.notifier = notifier;
        this.activity = activity;
        this.clock = clock;
    }

    @GET
    @Path("/tasks/{taskId}/comments")
    @Operation(summary = "List the comments on a task")
    public List<CommentResponse> list(@PathParam("taskId") long taskId) {
        Task task = task(taskId);
        access.project(task.projectId);
        List<Comment> comments = Comment.list("taskId = ?1 order by createdAt, id", taskId);
        Map<Long, User> authors = new HashMap<>();
        return comments.stream().map(c -> toResponse(c, authors.computeIfAbsent(c.authorId, id -> User.findById(id)))).toList();
    }

    /** Client viewers can comment too: it's how they ask questions about the plan. */
    @POST
    @Path("/tasks/{taskId}/comments")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Comment on a task",
            description = "Client viewers can comment too. An @handle in the text notifies that person.")
    public CommentResponse create(@PathParam("taskId") long taskId, @Valid @NotNull CommentRequest request) {
        Task task = task(taskId);
        Project project = access.project(task.projectId);
        Membership me = access.viewProject(project);
        User author = User.findById(me.userId);

        Comment comment = new Comment();
        comment.taskId = task.id;
        comment.authorId = me.userId;
        comment.body = request.body().strip();
        comment.createdAt = Instant.now(clock);
        comment.persist();

        // Everybody mentioned, and the assignee if they were not, in one go.
        Map<Long, NotificationType> recipients = new LinkedHashMap<>();
        for (long userId : mentionedUsers(project, comment.body)) {
            recipients.put(userId, NotificationType.MENTIONED);
        }
        if (task.assigneeId != null) {
            recipients.putIfAbsent(task.assigneeId, NotificationType.COMMENTED);
        }
        notifier.notifyEach(recipients, project.organisationId, me.userId,
                type -> author.name + (type == NotificationType.MENTIONED ? " mentioned you on " : " commented on ") + task.title,
                project.id, task.id);
        activity.record(project.organisationId, project.id, task.id, me.userId, "comment.added",
                author.name + " commented on " + task.title);
        return toResponse(comment, author);
    }

    @PUT
    @Path("/comments/{commentId}")
    @Transactional
    @Operation(summary = "Edit a comment",
            description = "Your own comments only.")
    public CommentResponse update(@PathParam("commentId") long commentId, @Valid @NotNull CommentRequest request) {
        Comment comment = comment(commentId);
        access.project(task(comment.taskId).projectId);
        if (!Objects.equals(comment.authorId, access.userId())) {
            throw ApiException.forbidden("You can only edit your own comments");
        }
        comment.body = request.body().strip();
        comment.editedAt = Instant.now(clock);
        return toResponse(comment, User.findById(comment.authorId));
    }

    @DELETE
    @Path("/comments/{commentId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Delete a comment",
            description = "Your own comments, or anyone's for owners and managers.")
    public void delete(@PathParam("commentId") long commentId) {
        Comment comment = comment(commentId);
        Project project = access.project(task(comment.taskId).projectId);
        Membership me = access.viewProject(project);
        if (!Objects.equals(comment.authorId, me.userId) && !me.role.canManage()) {
            throw ApiException.forbidden("You can only delete your own comments");
        }
        comment.delete();
    }

    /**
     * Members of the organisation whose handle is mentioned and who may see the project.
     * A client viewer of another client is left out, so that a mention does not tell them
     * the title of a task they cannot open.
     */
    static Set<Long> mentionedUsers(Project project, String body) {
        Set<String> handles = MentionParser.handles(body);
        Set<Long> ids = new HashSet<>();
        if (handles.isEmpty()) {
            return ids;
        }
        // Only those who may see the project, and the people in one query. A handle is the part of
        // the email before the @, so it can not be asked for in SQL.
        List<Long> members = Membership.forOrganisation(project.organisationId).stream()
                .filter(m -> OrgAccess.canSee(m, project)).map(m -> m.userId).toList();
        if (members.isEmpty()) {
            return ids;
        }
        for (User user : User.<User>list("id in ?1", members)) {
            if (handles.contains(user.handle())) {
                ids.add(user.id);
            }
        }
        return ids;
    }

    private static Task task(long taskId) {
        Task task = Task.findById(taskId);
        if (task == null) {
            throw ApiException.notFound("Task");
        }
        return task;
    }

    private static Comment comment(long commentId) {
        Comment comment = Comment.findById(commentId);
        if (comment == null) {
            throw ApiException.notFound("Comment");
        }
        return comment;
    }

    private static CommentResponse toResponse(Comment c, User author) {
        return new CommentResponse(c.id, c.taskId, c.authorId, author.name, author.handle(), c.body, c.createdAt, c.editedAt);
    }

    public record CommentRequest(@NotBlank @Size(max = 10000) String body) {
    }

    public record CommentResponse(long id, long taskId, long authorId, String authorName, String authorHandle,
                                  String body, Instant createdAt, Instant editedAt) {
    }
}
