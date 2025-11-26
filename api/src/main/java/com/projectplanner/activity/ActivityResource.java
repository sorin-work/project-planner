package com.projectplanner.activity;

import com.projectplanner.auth.User;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/api")
@Authenticated
public class ActivityResource {


    private final OrgAccess access;

    public ActivityResource(OrgAccess access) {
        this.access = access;
    }

    /** Organisation-wide feed. */
    @GET
    @Path("/orgs/{orgId}/activity")
    public List<ActivityResponse> forOrganisation(@PathParam("orgId") long orgId,
                                                  @QueryParam("limit") @DefaultValue("50") int limit) {
        Membership me = access.member(orgId);
        List<ActivityEntry> entries = ActivityEntry.find("organisationId", Sort.descending("createdAt", "id"), orgId)
                .page(Page.ofSize(limit)).list();
        return toResponses(entries);
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
