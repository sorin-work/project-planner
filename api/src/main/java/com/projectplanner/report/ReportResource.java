package com.projectplanner.report;

import com.projectplanner.common.ApiException;
import com.projectplanner.common.CsvWriter;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.report.TimeReport.GroupBy;
import com.projectplanner.report.TimeReportService.Filter;
import com.projectplanner.report.TimeReportService.UtilisationReport;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Time and utilisation reports for the team. Client viewers have no reports.
 * Each report comes as JSON for the page and as CSV with the same rows.
 */
@Path("/api/orgs/{orgId}/reports")
@Authenticated
public class ReportResource {

    private final OrgAccess access;
    private final TimeReportService reports;
    private final Clock clock;

    public ReportResource(OrgAccess access, TimeReportService reports, Clock clock) {
        this.access = access;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * Logged time per project, client, member or task. Members get their own time and no
     * amounts; owners and managers get everybody's, with the billable amount.
     */
    @GET
    @Path("/time")
    public TimeReportResponse time(@PathParam("orgId") long orgId,
                                   @QueryParam("preset") String preset,
                                   @QueryParam("from") LocalDate from,
                                   @QueryParam("to") LocalDate to,
                                   @QueryParam("groupBy") String groupBy,
                                   @QueryParam("userId") Long userId,
                                   @QueryParam("projectId") Long projectId,
                                   @QueryParam("clientId") Long clientId,
                                   @QueryParam("billable") Boolean billable) {
        Membership me = access.team(orgId);
        return timeReport(me, preset, from, to, groupBy, userId, projectId, clientId, billable);
    }

    @GET
    @Path("/time.csv")
    @Produces("text/csv")
    public Response timeCsv(@PathParam("orgId") long orgId,
                            @QueryParam("preset") String preset,
                            @QueryParam("from") LocalDate from,
                            @QueryParam("to") LocalDate to,
                            @QueryParam("groupBy") String groupBy,
                            @QueryParam("userId") Long userId,
                            @QueryParam("projectId") Long projectId,
                            @QueryParam("clientId") Long clientId,
                            @QueryParam("billable") Boolean billable) {
        Membership me = access.team(orgId);
        TimeReportResponse report = timeReport(me, preset, from, to, groupBy, userId, projectId, clientId, billable);
        boolean money = me.role.canManage();
        List<String> header = new ArrayList<>(List.of("from", "to", "group_by", "key", "label", "minutes", "billable_minutes"));
        if (money) {
            header.add("billable_amount");
        }
        CsvWriter csv = new CsvWriter().row(header);
        for (TimeRow row : report.rows()) {
            List<Object> values = new ArrayList<>(List.of(report.from(), report.to(), report.groupBy().name().toLowerCase(Locale.ROOT),
                    row.key(), row.label(), row.minutes(), row.billableMinutes()));
            if (money) {
                values.add(row.billableAmount());
            }
            csv.row(values);
        }
        return download(csv, "time-by-" + report.groupBy().name().toLowerCase(Locale.ROOT), report.from(), report.to());
    }

    /**
     * Billable time against capacity per team member, for owners and managers. Capacity is
     * what the workload page uses: weekly hours over the working days, counted up to today.
     */
    @GET
    @Path("/utilisation")
    public UtilisationResponse utilisation(@PathParam("orgId") long orgId,
                                           @QueryParam("preset") String preset,
                                           @QueryParam("from") LocalDate from,
                                           @QueryParam("to") LocalDate to) {
        access.manager(orgId);
        LocalDate today = Organisation.<Organisation>findById(orgId).today(clock);
        ReportRange range = ReportRange.resolve(preset, from, to, today);
        UtilisationReport report = reports.utilisation(orgId, range, today);
        return new UtilisationResponse(range.from(), range.to(), report.capacityTo(), report.workingDays(),
                report.result().rows(), report.result().total());
    }

    @GET
    @Path("/utilisation.csv")
    @Produces("text/csv")
    public Response utilisationCsv(@PathParam("orgId") long orgId,
                                   @QueryParam("preset") String preset,
                                   @QueryParam("from") LocalDate from,
                                   @QueryParam("to") LocalDate to) {
        UtilisationResponse report = utilisation(orgId, preset, from, to);
        CsvWriter csv = new CsvWriter().row("from", "to", "member", "minutes", "billable_minutes", "capacity_minutes", "utilisation_pct");
        for (Utilisation.Row row : report.rows()) {
            csv.row(report.from(), report.to(), row.name(), row.minutes(), row.billableMinutes(), row.capacityMinutes(),
                    row.utilisationPct() == null ? "" : row.utilisationPct());
        }
        return download(csv, "utilisation", report.from(), report.to());
    }

    private TimeReportResponse timeReport(Membership me, String preset, LocalDate from, LocalDate to, String groupBy,
                                          Long userId, Long projectId, Long clientId, Boolean billable) {
        long orgId = me.organisationId;
        ReportRange range = ReportRange.resolve(preset, from, to, Organisation.<Organisation>findById(orgId).today(clock));
        GroupBy group = parseGroupBy(groupBy);
        boolean manager = me.role.canManage();
        // A member only ever gets their own time. Asking for somebody else's says so,
        // whether or not that person exists.
        if (!manager && userId != null && !Objects.equals(userId, me.userId)) {
            throw ApiException.forbidden("You can only see your own time");
        }
        // The other filters have to name something the caller can see. A project, client or
        // person that is not there is a 404, not an empty report.
        if (manager && userId != null) {
            access.memberIn(orgId, userId);
        }
        if (projectId != null) {
            access.projectIn(orgId, projectId);
        }
        if (clientId != null) {
            access.clientIn(orgId, clientId);
        }
        Filter filter = new Filter(manager ? userId : Long.valueOf(me.userId), projectId, clientId, billable);
        TimeReport.Result result = reports.time(orgId, range, group, filter, manager);
        return new TimeReportResponse(range.from(), range.to(), group,
                result.rows().stream().map(row -> TimeRow.of(row, manager)).toList(), TimeRow.of(result.total(), manager));
    }

    private static GroupBy parseGroupBy(String text) {
        if (text == null || text.isBlank()) {
            return GroupBy.PROJECT;
        }
        try {
            return GroupBy.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_group", "Group by project, client, member or task");
        }
    }

    private static Response download(CsvWriter csv, String name, LocalDate from, LocalDate to) {
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + name + "-" + from + "-to-" + to + ".csv\"")
                .build();
    }

    /** {@code billableAmount} is only filled in for owners and managers. */
    public record TimeRow(String key, String label, long minutes, long billableMinutes, BigDecimal billableAmount) {
        static TimeRow of(TimeReport.Row row, boolean withMoney) {
            return new TimeRow(row.key(), row.label(), row.minutes(), row.billableMinutes(), withMoney ? row.billableAmount() : null);
        }
    }

    public record TimeReportResponse(LocalDate from, LocalDate to, GroupBy groupBy, List<TimeRow> rows, TimeRow total) {
    }

    /**
     * {@code capacityTo} is the last day capacity was counted for: the end of the range, or
     * today when the range runs into the future. {@code workingDays} are the days counted.
     */
    public record UtilisationResponse(LocalDate from, LocalDate to, LocalDate capacityTo, int workingDays,
                                      List<Utilisation.Row> rows, Utilisation.Row total) {
    }
}
