package com.projectplanner.time;

import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.time.TimeDtos.MissingResponse;
import com.projectplanner.time.TimeDtos.RemindRequest;
import com.projectplanner.time.TimeDtos.RemindResponse;
import com.projectplanner.time.TimeDtos.ReviewRequest;
import com.projectplanner.time.TimeDtos.SubmitRequest;
import com.projectplanner.time.TimeDtos.TimesheetSummary;
import com.projectplanner.time.TimeDtos.WeekResponse;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Path("/api/orgs/{orgId}/timesheets")
@Authenticated
@Tag(name = "Timesheets")
public class TimesheetResource {

    private final OrgAccess access;
    private final TimesheetService timesheets;
    private final Clock clock;

    public TimesheetResource(OrgAccess access, TimesheetService timesheets, Clock clock) {
        this.access = access;
        this.timesheets = timesheets;
        this.clock = clock;
    }

    /**
     * One week of time for a person: the signed-in user unless a manager asks for someone else.
     * Without a date it is the current week by the organisation's date.
     */
    @GET
    @Path("/week")
    @Operation(summary = "One week of time for a person",
            description = "For the team: owners, managers and members, not client viewers. Your own week, or with userId someone else's for owners and managers.")
    public WeekResponse week(@PathParam("orgId") long orgId,
                             @Parameter(description = "Any day of the week. Today when left out.") @QueryParam("date") LocalDate date,
                             @Parameter(description = "Yourself when left out.") @QueryParam("userId") Long userId) {
        Membership me = access.team(orgId);
        long user = userId == null ? me.userId : userId;
        if (user != me.userId && !me.role.canManage()) {
            throw ApiException.forbidden("You can only see your own timesheets");
        }
        if (Membership.find(orgId, user).isEmpty()) {
            throw ApiException.notFound("Member");
        }
        return timesheets.week(orgId, user, date != null ? date : Organisation.<Organisation>findById(orgId).today(clock));
    }

    @POST
    @Path("/submit")
    @Transactional
    @Operation(summary = "Submit a week for approval",
            description = "For the team: owners, managers and members, not client viewers. The week is locked until it is sent back or withdrawn.")
    @Fails(status = 400, code = "empty_timesheet", when = "no time is logged in the week")
    @Fails(status = 409, code = "timer_running", when = "a timer is still running")
    @Fails(status = 409, code = "already_submitted", when = "the week is already submitted or approved")
    public WeekResponse submit(@PathParam("orgId") long orgId, @Valid @NotNull SubmitRequest request) {
        Membership me = access.team(orgId);
        Timesheet sheet = timesheets.submit(me, request.weekStart());
        return timesheets.week(orgId, me.userId, sheet.weekStart);
    }

    @POST
    @Path("/withdraw")
    @Transactional
    @Operation(summary = "Take a submitted week back",
            description = "For the team: owners, managers and members, not client viewers.")
    @Fails(status = 409, code = "not_submitted", when = "the week is not waiting for review")
    public WeekResponse withdraw(@PathParam("orgId") long orgId, @Valid @NotNull SubmitRequest request) {
        Membership me = access.team(orgId);
        Timesheet sheet = timesheets.withdraw(me, request.weekStart());
        return timesheets.week(orgId, me.userId, sheet.weekStart);
    }

    /** The approval queue for managers; by default only weeks waiting for review. */
    @GET
    @Operation(summary = "The approval queue",
            description = "For owners and managers. Weeks waiting for review unless another status is asked for.")
    public List<TimesheetSummary> list(@PathParam("orgId") long orgId,
                                       @QueryParam("status") @DefaultValue("SUBMITTED") TimesheetStatus status,
                                       @QueryParam("limit") @DefaultValue("50") int limit) {
        access.manager(orgId);
        List<Timesheet> sheets = Timesheet.<Timesheet>find(
                        "organisationId = ?1 and status = ?2 order by weekStart desc, id", orgId, status)
                .page(0, Math.max(1, Math.min(200, limit))).list();
        return timesheets.summaries(orgId, sheets);
    }

    /**
     * Who has not submitted a week that is over: drafts and weeks that were sent back, with
     * the time logged so far. For managers.
     */
    @GET
    @Path("/missing")
    @Operation(summary = "Who has not submitted a week that is over",
            description = "For owners and managers. Drafts and weeks that were sent back, with the time logged so far. Without weekStart it is the latest week that is over.")
    @Fails(status = 400, code = "week_not_over", when = "the week has not ended yet")
    public MissingResponse missing(@PathParam("orgId") long orgId,
                                   @Parameter(description = "Any day of the week.") @QueryParam("weekStart") LocalDate weekStart) {
        access.manager(orgId);
        return timesheets.missing(orgId, weekStart);
    }

    /** Asks people to submit a missing week. The answer says who was reminded and who was left out, and why. */
    @POST
    @Path("/remind")
    @Transactional
    @Operation(summary = "Remind people to submit a week they have not submitted",
            description = "For owners and managers. The answer says who was reminded, and who was left out and why.")
    @Fails(status = 400, code = "week_not_over", when = "the week has not ended yet")
    public RemindResponse remind(@PathParam("orgId") long orgId, @Valid @NotNull RemindRequest request) {
        Membership me = access.manager(orgId);
        return timesheets.remind(me, request.weekStart(), request.userIds());
    }

    @GET
    @Path("/{sheetId}")
    @Operation(summary = "Get a timesheet",
            description = "For the team: owners, managers and members, not client viewers. Your own, or anyone's for owners and managers.")
    public WeekResponse get(@PathParam("orgId") long orgId, @PathParam("sheetId") long sheetId) {
        Membership me = access.team(orgId);
        Timesheet sheet = find(orgId, sheetId);
        if (!Objects.equals(sheet.userId, me.userId) && !me.role.canManage()) {
            throw ApiException.notFound("Timesheet");
        }
        return timesheets.week(orgId, sheet.userId, sheet.weekStart);
    }

    @POST
    @Path("/{sheetId}/approve")
    @Transactional
    @Operation(summary = "Approve a timesheet",
            description = "For owners and managers. A manager cannot review their own week, an owner can.")
    @Fails(status = 409, code = "not_submitted", when = "the week is not waiting for review")
    public WeekResponse approve(@PathParam("orgId") long orgId, @PathParam("sheetId") long sheetId) {
        Membership me = access.manager(orgId);
        Timesheet sheet = timesheets.approve(me, find(orgId, sheetId));
        return timesheets.week(orgId, sheet.userId, sheet.weekStart);
    }

    @POST
    @Path("/{sheetId}/reject")
    @Transactional
    @Operation(summary = "Send a timesheet back with a note",
            description = "For owners and managers. The week is unlocked again.")
    @Fails(status = 409, code = "not_submitted", when = "the week is not waiting for review")
    public WeekResponse reject(@PathParam("orgId") long orgId, @PathParam("sheetId") long sheetId,
                               @Valid ReviewRequest request) {
        Membership me = access.manager(orgId);
        Timesheet sheet = timesheets.reject(me, find(orgId, sheetId), request == null ? null : request.note());
        return timesheets.week(orgId, sheet.userId, sheet.weekStart);
    }

    private static Timesheet find(long orgId, long sheetId) {
        Timesheet sheet = Timesheet.findById(sheetId);
        if (sheet == null || !Objects.equals(sheet.organisationId, orgId)) {
            throw ApiException.notFound("Timesheet");
        }
        return sheet;
    }
}
