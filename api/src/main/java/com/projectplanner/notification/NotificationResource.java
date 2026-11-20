package com.projectplanner.notification;

import com.projectplanner.auth.CurrentUser;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/api/notifications")
@Authenticated
public class NotificationResource {

    private final CurrentUser currentUser;
    private final Clock clock;

    public NotificationResource(CurrentUser currentUser, Clock clock) {
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GET
    public List<NotificationResponse> list(@QueryParam("orgId") Long orgId,
                                           @QueryParam("unread") @DefaultValue("false") boolean unreadOnly,
                                           @QueryParam("limit") @DefaultValue("50") int limit) {
        StringBuilder query = new StringBuilder("userId = :user");
        Parameters params = Parameters.with("user", currentUser.id());
        if (orgId != null) {
            query.append(" and organisationId = :org");
            params.and("org", orgId);
        }
        if (unreadOnly) {
            query.append(" and readAt is null");
        }
        query.append(" order by createdAt desc, id desc");
        List<Notification> items = Notification.<Notification>find(query.toString(), params)
                .page(Page.ofSize(Math.max(1, Math.min(200, limit)))).list();
        Set<Long> actorIds = items.stream().map(n -> n.actorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!actorIds.isEmpty()) {
            User.<User>list("id in ?1", actorIds).forEach(u -> names.put(u.id, u.name));
        }
        return items.stream().map(n -> new NotificationResponse(n.id, n.organisationId, n.type, n.message,
                n.actorId == null ? null : names.get(n.actorId), n.projectId, n.taskId, n.weekStart, n.readAt != null,
                n.createdAt)).toList();
    }

    @GET
    @Path("/unread-count")
    public UnreadCount unreadCount(@QueryParam("orgId") Long orgId) {
        long count = orgId == null
                ? Notification.count("userId = ?1 and readAt is null", currentUser.id())
                : Notification.count("userId = ?1 and organisationId = ?2 and readAt is null", currentUser.id(), orgId);
        return new UnreadCount(count);
    }

    @POST
    @Path("/{id}/read")
    @Transactional
    @ResponseStatus(204)
    public void markRead(@PathParam("id") long id) {
        Notification notification = Notification.findById(id);
        if (notification == null || notification.userId != currentUser.id()) {
            throw ApiException.notFound("Notification");
        }
        if (notification.readAt == null) {
            notification.readAt = Instant.now(clock);
        }
    }

    @POST
    @Path("/read-all")
    @Transactional
    @ResponseStatus(204)
    public void markAllRead(@QueryParam("orgId") Long orgId) {
        Instant now = Instant.now(clock);
        if (orgId == null) {
            Notification.update("readAt = ?1 where userId = ?2 and readAt is null", now, currentUser.id());
        } else {
            Notification.update("readAt = ?1 where userId = ?2 and organisationId = ?3 and readAt is null", now, currentUser.id(), orgId);
        }
    }

    public record NotificationResponse(long id, long organisationId, NotificationType type, String message,
                                       String actorName, Long projectId, Long taskId, LocalDate weekStart, boolean read,
                                       Instant createdAt) {
    }

    public record UnreadCount(long count) {
    }
}
