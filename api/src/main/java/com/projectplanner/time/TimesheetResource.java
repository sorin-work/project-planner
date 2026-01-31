package com.projectplanner.time;

import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Path("/api/orgs/{orgId}/timesheets")
@Authenticated
public class TimesheetResource {

    private final OrgAccess access;
    private final TimesheetService timesheets;
    private final Clock clock;

    public TimesheetResource(OrgAccess access, TimesheetService timesheets, Clock clock) {
        this.access = access;
        this.timesheets = timesheets;
        this.clock = clock;
    }

    /** One week of time for a person: the signed-in user unless a manager asks for someone else. */
    @GET
    @Path("/week")
    public WeekResponse week(@PathParam("orgId") long orgId,
                             @QueryParam("date") LocalDate date,
                             @QueryParam("userId") Long userId) {
        Membership me = access.team(orgId);
        long user = userId == null ? me.userId : userId;
        if (user != me.userId && !me.role.canManage()) {
            throw ApiException.forbidden("You can only see your own timesheets");
        }
        if (Membership.find(orgId, user).isEmpty()) {
            throw ApiException.notFound("Member");
        }
        return timesheets.week(orgId, user, date != null ? date : LocalDate.now(clock));
    }

    @POST
    @Path("/submit")
    @Transactional
    public WeekResponse submit(@PathParam("orgId") long orgId, @Valid @NotNull SubmitRequest request) {
        Membership me = access.team(orgId);
        Timesheet sheet = timesheets.submit(me, request.weekStart());
        return timesheets.week(orgId, me.userId, sheet.weekStart);
    }

    @POST
    @Path("/withdraw")
    @Transactional
    public WeekResponse withdraw(@PathParam("orgId") long orgId, @Valid @NotNull SubmitRequest request) {
        Membership me = access.team(orgId);
        Timesheet sheet = timesheets.withdraw(me, request.weekStart());
        return timesheets.week(orgId, me.userId, sheet.weekStart);
    }

    /** The approval queue for managers; by default only weeks waiting for review. */
    @GET
    public List<TimesheetSummary> list(@PathParam("orgId") long orgId,
                                       @QueryParam("status") @DefaultValue("SUBMITTED") TimesheetStatus status,
                                       @QueryParam("limit") @DefaultValue("50") int limit) {
        access.manager(orgId);
        List<Timesheet> sheets = Timesheet.<Timesheet>find(
                        "organisationId = ?1 and status = ?2 order by weekStart desc, id", orgId, status)
                .page(0, Math.max(1, Math.min(200, limit))).list();
        return timesheets.summaries(orgId, sheets);
    }

    @GET
    @Path("/{sheetId}")
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
    public WeekResponse approve(@PathParam("orgId") long orgId, @PathParam("sheetId") long sheetId) {
        Membership me = access.manager(orgId);
        Timesheet sheet = timesheets.approve(me, find(orgId, sheetId));
        return timesheets.week(orgId, sheet.userId, sheet.weekStart);
    }

    @POST
    @Path("/{sheetId}/reject")
    @Transactional
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
