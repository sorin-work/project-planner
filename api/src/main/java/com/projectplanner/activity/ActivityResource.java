package com.projectplanner.activity;

import com.projectplanner.auth.User;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/api")
@Authenticated
@Tag(name = "Collaboration")
public class ActivityResource {

    private static final int MAX_LIMIT = 200;

    private final OrgAccess access;

    public ActivityResource(OrgAccess access) {
        this.access = access;
    }

    /** Organisation-wide feed for the team. Client viewers use the per-project feed. */
    @GET
    @Path("/orgs/{orgId}/activity")
    @Operation(summary = "What happened across the organisation, newest first",
            description = "Client viewers get an empty list here and use the feed of a project.")
    public List<ActivityResponse> forOrganisation(@PathParam("orgId") long orgId,
                                                  @QueryParam("limit") @DefaultValue("50") int limit) {
        Membership me = access.member(orgId);
        if (me.role == Role.CLIENT) {
            return List.of();
        }
        List<ActivityEntry> entries = ActivityEntry.find("organisationId", Sort.descending("createdAt", "id"), orgId)
                .page(Page.ofSize(clamp(limit))).list();
        return toResponses(entries);
    }

    @GET
    @Path("/projects/{projectId}/activity")
    @Operation(summary = "What happened in a project, newest first")
    public List<ActivityResponse> forProject(@PathParam("projectId") long projectId,
                                             @QueryParam("limit") @DefaultValue("50") int limit) {
        Project project = access.project(projectId);
        List<ActivityEntry> entries = ActivityEntry.find("projectId", Sort.descending("createdAt", "id"), project.id)
                .page(Page.ofSize(clamp(limit))).list();
        return toResponses(entries);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    static List<ActivityResponse> toResponses(List<ActivityEntry> entries) {
        Set<Long> actorIds = entries.stream().map(e -> e.actorId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!actorIds.isEmpty()) {
            User.<User>list("id in ?1", actorIds).forEach(u -> names.put(u.id, u.name));
        }
        return entries.stream()
                .map(e -> new ActivityResponse(e.id, e.projectId, e.taskId, e.actorId,
                        e.actorId == null ? null : names.get(e.actorId), e.action, e.summary, e.createdAt))
                .toList();
    }

    public record ActivityResponse(long id, Long projectId, Long taskId, Long actorId, String actorName,
                                   String action, String summary, Instant createdAt) {
    }
}
