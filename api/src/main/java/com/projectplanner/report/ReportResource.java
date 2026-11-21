package com.projectplanner.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.CsvWriter;
import com.projectplanner.common.Fails;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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
@Tag(name = "Reports")
public class ReportResource {

    private static final String RANGE = "Either a preset, which is worked out from the organisation's date, or from and to."
            + " Both days are included, and a range covers at most two years.";
    static final String APPROVED = "Only time of weeks that are approved: what can be invoiced.";
    private static final String PRESET = "this_week, last_week, this_month or last_month. Wins over from and to.";
    private static final String GROUP_BY = "project (the default), client, member or task.";

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
    @Operation(summary = "Logged time per project, client, member or task",
            description = "For the team, not client viewers. Members get their own time, without amounts. " + RANGE
                    + " A project, client or person in a filter that is not in the organisation answers 404.")
    @Fails(status = 400, code = "invalid_range", when = "there is no preset and from or to is missing, to is before from, or the range is longer than two years")
    @Fails(status = 400, code = "invalid_preset", when = "the preset is not one of the four")
    @Fails(status = 400, code = "invalid_group", when = "groupBy is not project, client, member or task")
    public TimeReportResponse time(@PathParam("orgId") long orgId,
                                   @Parameter(description = PRESET) @QueryParam("preset") String preset,
                                   @QueryParam("from") LocalDate from,
                                   @QueryParam("to") LocalDate to,
                                   @Parameter(description = GROUP_BY) @QueryParam("groupBy") String groupBy,
                                   @QueryParam("userId") Long userId,
                                   @QueryParam("projectId") Long projectId,
                                   @QueryParam("clientId") Long clientId,
                                   @QueryParam("billable") Boolean billable,
                                   @Parameter(description = APPROVED) @QueryParam("approvedOnly") boolean approvedOnly) {
        Membership me = access.team(orgId);
        return timeReport(me, preset, from, to, groupBy, userId, projectId, clientId, billable, approvedOnly);
    }

    @GET
    @Path("/time.csv")
    @Produces("text/csv")
    @Operation(summary = "The time report as CSV",
            description = "The same rows, range, filters and access rules as the JSON form, without the total.")
    @APIResponse(responseCode = "200",
            description = "A header row and one row per group, as an attachment: from, to, group_by, key, label, minutes,"
                    + " billable_minutes and, for owners and managers, billable_amount.",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = SchemaType.STRING)))
    @Fails(status = 400, code = "invalid_range", when = "there is no preset and from or to is missing, to is before from, or the range is longer than two years")
    @Fails(status = 400, code = "invalid_preset", when = "the preset is not one of the four")
    @Fails(status = 400, code = "invalid_group", when = "groupBy is not project, client, member or task")
    public Response timeCsv(@PathParam("orgId") long orgId,
                            @Parameter(description = PRESET) @QueryParam("preset") String preset,
                            @QueryParam("from") LocalDate from,
                            @QueryParam("to") LocalDate to,
                            @Parameter(description = GROUP_BY) @QueryParam("groupBy") String groupBy,
                            @QueryParam("userId") Long userId,
                            @QueryParam("projectId") Long projectId,
                            @QueryParam("clientId") Long clientId,
                            @QueryParam("billable") Boolean billable,
                            @Parameter(description = APPROVED) @QueryParam("approvedOnly") boolean approvedOnly) {
        Membership me = access.team(orgId);
        TimeReportResponse report = timeReport(me, preset, from, to, groupBy, userId, projectId, clientId, billable, approvedOnly);
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
     * what the workload page uses: weekly hours over the working days. Time and capacity
     * are both counted up to today.
     */
    @GET
    @Path("/utilisation")
    @Operation(summary = "Billable time against capacity, per team member",
            description = "For owners and managers. " + RANGE + " Time and capacity are counted up to today when the range runs"
                    + " into the future. Capacity is the weekly hours of each member as they are now, spread over Monday to"
                    + " Friday. People who are no longer in the organisation are not listed.")
    @Fails(status = 400, code = "invalid_range", when = "there is no preset and from or to is missing, to is before from, or the range is longer than two years")
    @Fails(status = 400, code = "invalid_preset", when = "the preset is not one of the four")
    public UtilisationResponse utilisation(@PathParam("orgId") long orgId,
                                           @Parameter(description = PRESET) @QueryParam("preset") String preset,
                                           @QueryParam("from") LocalDate from,
                                           @QueryParam("to") LocalDate to) {
        access.manager(orgId);
        LocalDate today = Organisation.<Organisation>findById(orgId).today(clock);
        ReportRange range = ReportRange.resolve(preset, from, to, today);
        UtilisationReport report = reports.utilisation(orgId, range, today);
        return new UtilisationResponse(range.from(), range.to(), report.countedTo(), report.workingDays(),
                report.result().rows().stream().map(UtilisationRow::of).toList(), UtilisationRow.of(report.result().total()));
    }

    @GET
    @Path("/utilisation.csv")
    @Produces("text/csv")
    @Operation(summary = "The utilisation report as CSV",
            description = "The same rows, range and access rules as the JSON form, without the total.")
    @APIResponse(responseCode = "200",
            description = "A header row and one row per team member, as an attachment: from, to, member, minutes,"
                    + " billable_minutes, capacity_minutes and utilisation_pct.",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = SchemaType.STRING)))
    @Fails(status = 400, code = "invalid_range", when = "there is no preset and from or to is missing, to is before from, or the range is longer than two years")
    @Fails(status = 400, code = "invalid_preset", when = "the preset is not one of the four")
    public Response utilisationCsv(@PathParam("orgId") long orgId,
                                   @Parameter(description = PRESET) @QueryParam("preset") String preset,
                                   @QueryParam("from") LocalDate from,
                                   @QueryParam("to") LocalDate to) {
        UtilisationResponse report = utilisation(orgId, preset, from, to);
        CsvWriter csv = new CsvWriter().row("from", "to", "member", "minutes", "billable_minutes", "capacity_minutes", "utilisation_pct");
        for (UtilisationRow row : report.rows()) {
            csv.row(report.from(), report.to(), row.name(), row.minutes(), row.billableMinutes(), row.capacityMinutes(),
                    row.utilisationPct() == null ? "" : row.utilisationPct());
        }
        return download(csv, "utilisation", report.from(), report.to());
    }

    private TimeReportResponse timeReport(Membership me, String preset, LocalDate from, LocalDate to, String groupBy,
                                          Long userId, Long projectId, Long clientId, Boolean billable, boolean approvedOnly) {
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
        // person that is not there is a 404, not an empty report. Somebody who has left the
        // organisation and has time in it is still there to be asked for.
        if (manager && userId != null) {
            access.personIn(orgId, userId);
        }
        if (projectId != null) {
            access.projectIn(orgId, projectId);
        }
        if (clientId != null) {
            access.clientIn(orgId, clientId);
        }
        Filter filter = new Filter(manager ? userId : Long.valueOf(me.userId), projectId, clientId, billable, approvedOnly);
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

    /** {@code billableAmount} is only there for owners and managers. Members get no such field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeRow(String key, String label, long minutes, long billableMinutes, BigDecimal billableAmount) {
        static TimeRow of(TimeReport.Row row, boolean withMoney) {
            return new TimeRow(row.key(), row.label(), row.minutes(), row.billableMinutes(), withMoney ? row.billableAmount() : null);
        }
    }

    public record TimeReportResponse(LocalDate from, LocalDate to, GroupBy groupBy, List<TimeRow> rows, TimeRow total) {
    }

    /**
     * {@code userId} is left out of the total, and {@code utilisationPct} where there is no
     * capacity to compare with.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UtilisationRow(Long userId, String name, long minutes, long billableMinutes, long capacityMinutes, Double utilisationPct) {
        static UtilisationRow of(Utilisation.Row row) {
            return new UtilisationRow(row.userId(), row.name(), row.minutes(), row.billableMinutes(), row.capacityMinutes(), row.utilisationPct());
        }
    }

    /**
     * {@code countedTo} is the last day time and capacity were counted for: the end of the
     * range, or today when the range runs into the future. It is left out when the whole
     * range lies in the future. {@code workingDays} are the working days counted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UtilisationResponse(LocalDate from, LocalDate to, LocalDate countedTo, int workingDays,
                                      List<UtilisationRow> rows, UtilisationRow total) {
    }
}
