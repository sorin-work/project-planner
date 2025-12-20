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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Path("/api")
@Authenticated
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
    public List<CommentResponse> list(@PathParam("taskId") long taskId) {
        Task task = task(taskId);
        access.project(task.projectId);
        List<Comment> comments = Comment.list("taskId = ?1 order by createdAt, id", taskId);
        Map<Long, User> authors = new HashMap<>();
        return comments.stream().map(c -> toResponse(c, authors.computeIfAbsent(c.authorId, id -> User.findById(id)))).toList();
    }

    @POST
    @Path("/tasks/{taskId}/comments")
    @Transactional
    @ResponseStatus(201)
    public CommentResponse create(@PathParam("taskId") long taskId, @Valid @NotNull CommentRequest request) {
        Task task = task(taskId);
        Project project = access.project(task.projectId);
        Membership me = access.editProject(project);
        User author = User.findById(me.userId);

        Comment comment = new Comment();
        comment.taskId = task.id;
        comment.authorId = me.userId;
        comment.body = request.body().strip();
        comment.createdAt = Instant.now(clock);
        comment.persist();

        Set<Long> notified = new HashSet<>();
        for (long userId : mentionedUsers(project.organisationId, comment.body)) {
            if (notified.add(userId)) {
                notifier.notify(userId, project.organisationId, me.userId, NotificationType.MENTIONED,
                        author.name + " mentioned you on " + task.title, project.id, task.id);
            }
        }
        if (task.assigneeId != null && !notified.contains(task.assigneeId)) {
            notifier.notify(task.assigneeId, project.organisationId, me.userId, NotificationType.COMMENTED,
                    author.name + " commented on " + task.title, project.id, task.id);
        }
        activity.record(project.organisationId, project.id, task.id, me.userId, "comment.added",
                author.name + " commented on " + task.title);
        return toResponse(comment, author);
    }

    @DELETE
    @Path("/comments/{commentId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("commentId") long commentId) {
        Comment comment = comment(commentId);
        Project project = access.project(task(comment.taskId).projectId);
        Membership me = access.viewProject(project);
        if (!Objects.equals(comment.authorId, me.userId)) {
            throw ApiException.forbidden("You can only delete your own comments");
        }
        comment.delete();
    }

    /** Team members (and client viewers) of the organisation whose handle is mentioned. */
    static Set<Long> mentionedUsers(long organisationId, String body) {
        Set<String> handles = MentionParser.handles(body);
        Set<Long> ids = new HashSet<>();
        if (handles.isEmpty()) {
            return ids;
        }
        for (Membership m : Membership.forOrganisation(organisationId)) {
            User user = User.findById(m.userId);
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
