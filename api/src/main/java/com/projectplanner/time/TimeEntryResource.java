package com.projectplanner.time;

import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.time.TimeDtos.StartTimerRequest;
import com.projectplanner.time.TimeDtos.TimeEntryRequest;
import com.projectplanner.time.TimeDtos.TimeEntryResponse;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Path("/api")
@Authenticated
public class TimeEntryResource {

    private final OrgAccess access;
    private final TimeService time;

    public TimeEntryResource(OrgAccess access, TimeService time) {
        this.access = access;
        this.time = time;
    }

    /** Members see their own time; owners and managers can look at anyone's. */
    @GET
    @Path("/orgs/{orgId}/time-entries")
    public List<TimeEntryResponse> list(@PathParam("orgId") long orgId,
                                        @QueryParam("from") LocalDate from,
                                        @QueryParam("to") LocalDate to,
                                        @QueryParam("userId") Long userId,
                                        @QueryParam("projectId") Long projectId,
                                        @QueryParam("taskId") Long taskId) {
        Membership me = access.team(orgId);
        if (from == null || to == null) {
            throw ApiException.badRequest("range_required", "Pass a from and to date");
        }
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) {
            throw ApiException.badRequest("invalid_range", "The range must run forwards and cover at most 400 days");
        }
        Long user = userId;
        if (!me.role.canManage()) {
            if (user != null && !Objects.equals(user, me.userId)) {
                throw ApiException.forbidden("You can only see your own time");
            }
            user = me.userId;
        }
        StringBuilder query = new StringBuilder("organisationId = :org and entryDate between :from and :to");
        Parameters params = Parameters.with("org", orgId).and("from", from).and("to", to);
        if (user != null) {
            query.append(" and userId = :user");
            params.and("user", user);
        }
        if (projectId != null) {
            query.append(" and projectId = :project");
            params.and("project", projectId);
        }
        if (taskId != null) {
            query.append(" and taskId = :task");
            params.and("task", taskId);
        }
        query.append(" order by entryDate desc, id desc");
        return time.toResponses(TimeEntry.list(query.toString(), params));
    }

    @POST
    @Path("/orgs/{orgId}/time-entries")
    @Transactional
    @ResponseStatus(201)
    public TimeEntryResponse create(@PathParam("orgId") long orgId, @Valid @NotNull TimeEntryRequest request) {
        Membership me = access.team(orgId);
        return time.toResponse(time.create(me, request));
    }

    @PUT
    @Path("/orgs/{orgId}/time-entries/{entryId}")
    @Transactional
    public TimeEntryResponse update(@PathParam("orgId") long orgId, @PathParam("entryId") long entryId,
                                    @Valid @NotNull TimeEntryRequest request) {
        Membership me = access.team(orgId);
        TimeEntry entry = find(orgId, entryId);
        time.update(me, entry, request);
        return time.toResponse(entry);
    }

    @DELETE
    @Path("/orgs/{orgId}/time-entries/{entryId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("orgId") long orgId, @PathParam("entryId") long entryId) {
        Membership me = access.team(orgId);
        time.delete(me, find(orgId, entryId));
    }

    @GET
    @Path("/orgs/{orgId}/timer")
    public Response timer(@PathParam("orgId") long orgId) {
        Membership me = access.team(orgId);
        return TimeEntry.runningFor(orgId, me.userId)
                .map(e -> Response.ok(time.toResponse(e)).build())
                .orElseGet(() -> Response.noContent().build());
    }

    @POST
    @Path("/orgs/{orgId}/timer/start")
    @Transactional
    @ResponseStatus(201)
    public TimeEntryResponse start(@PathParam("orgId") long orgId, @Valid @NotNull StartTimerRequest request) {
        Membership me = access.team(orgId);
        return time.toResponse(time.start(me, request));
    }

    @POST
    @Path("/orgs/{orgId}/timer/stop")
    @Transactional
    public TimeEntryResponse stop(@PathParam("orgId") long orgId) {
        Membership me = access.team(orgId);
        TimeEntry entry = time.stop(me).orElseThrow(() -> ApiException.conflict("no_timer", "No timer is running"));
        return time.toResponse(entry);
    }

    private static TimeEntry find(long orgId, long entryId) {
        TimeEntry entry = TimeEntry.findById(entryId);
        if (entry == null || !Objects.equals(entry.organisationId, orgId)) {
            throw ApiException.notFound("Time entry");
        }
        return entry;
    }
}
