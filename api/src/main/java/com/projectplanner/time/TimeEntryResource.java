package com.projectplanner.time;

import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Path("/api")
@Authenticated
@Tag(name = "Time")
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
    @Operation(summary = "List the time entries in a range of dates",
            description = "For the team: owners, managers and members, not client viewers. Members get their own time. Owners and managers get everyone's, or one person's with userId.")
    @Fails(status = 400, code = "range_required", when = "from or to is missing")
    @Fails(status = 400, code = "invalid_range", when = "to is before from, or the range is longer than 400 days")
    public List<TimeEntryResponse> list(@PathParam("orgId") long orgId,
                                        @Parameter(required = true) @QueryParam("from") LocalDate from,
                                        @Parameter(required = true) @QueryParam("to") LocalDate to,
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
    @Operation(summary = "Log time",
            description = "For the team: owners, managers and members, not client viewers. With a clientKey the same request can be sent again after an answer that got lost: it answers with the entry the first one made, as that entry is now.")
    @Fails(status = 400, code = "unknown_project", when = "projectId is not a project of this organisation")
    @Fails(status = 400, code = "project_done", when = "the project is finished")
    @Fails(status = 400, code = "unknown_task", when = "taskId is not a task of that project")
    @Fails(status = 400, code = "date_in_future", when = "the date is too far ahead")
    @Fails(status = 409, code = "timesheet_locked", when = "the week of the entry is submitted or approved")
    @Fails(status = 409, code = "client_key_reused", when = "the clientKey was used before, for an entry on another project, task or day")
    public TimeEntryResponse create(@PathParam("orgId") long orgId, @Valid @NotNull TimeEntryRequest request) {
        Membership me = access.team(orgId);
        return time.toResponse(time.create(me, request));
    }

    @PUT
    @Path("/orgs/{orgId}/time-entries/{entryId}")
    @Transactional
    @Operation(summary = "Change a time entry",
            description = "For the team: owners, managers and members, not client viewers. Your own entries only, owners and managers anyone's.")
    @Fails(status = 400, code = "unknown_project", when = "projectId is not a project of this organisation")
    @Fails(status = 400, code = "project_done", when = "the project is finished")
    @Fails(status = 400, code = "unknown_task", when = "taskId is not a task of that project")
    @Fails(status = 400, code = "date_in_future", when = "the date is too far ahead")
    @Fails(status = 409, code = "timesheet_locked", when = "the week of the entry is submitted or approved")
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
    @Operation(summary = "Delete a time entry",
            description = "For the team: owners, managers and members, not client viewers. Your own entries only, owners and managers anyone's.")
    @Fails(status = 409, code = "timesheet_locked", when = "the week of the entry is submitted or approved")
    public void delete(@PathParam("orgId") long orgId, @PathParam("entryId") long entryId) {
        Membership me = access.team(orgId);
        time.delete(me, find(orgId, entryId));
    }

    @GET
    @Path("/orgs/{orgId}/timer")
    @Operation(summary = "The timer you have running, if any",
            description = "For the team: owners, managers and members, not client viewers.")
    @APIResponse(responseCode = "200", description = "The running timer.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TimeEntryResponse.class)))
    @APIResponse(responseCode = "204", description = "No timer is running.")
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
    @Operation(summary = "Start a timer",
            description = "For the team: owners, managers and members, not client viewers. A timer that is already running is stopped and saved first.")
    @Fails(status = 400, code = "unknown_project", when = "projectId is not a project of this organisation")
    @Fails(status = 400, code = "project_done", when = "the project is finished")
    @Fails(status = 400, code = "unknown_task", when = "taskId is not a task of that project")
    @Fails(status = 409, code = "timesheet_locked", when = "the week of the entry is submitted or approved")
    public TimeEntryResponse start(@PathParam("orgId") long orgId, @Valid @NotNull StartTimerRequest request) {
        Membership me = access.team(orgId);
        return time.toResponse(time.start(me, request));
    }

    @POST
    @Path("/orgs/{orgId}/timer/stop")
    @Transactional
    @Operation(summary = "Stop the running timer",
            description = "For the team: owners, managers and members, not client viewers. The time is booked on the day the timer was started.")
    @Fails(status = 409, code = "no_timer", when = "no timer is running")
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
