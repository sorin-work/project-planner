package com.projectplanner.time;

import com.projectplanner.auth.User;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.CsvWriter;
import com.projectplanner.common.Money;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.RateBook;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Path("/api/orgs/{orgId}/exports")
@Authenticated
public class ExportResource {

    private final OrgAccess access;

    public ExportResource(OrgAccess access) {
        this.access = access;
    }

    /**
     * Time entries as CSV, one row per entry. Members can only export their own
     * time and don't get the rate and amount columns.
     */
    @GET
    @Path("/timesheets.csv")
    @Produces("text/csv")
    public Response timesheets(@PathParam("orgId") long orgId,
                               @QueryParam("from") LocalDate from,
                               @QueryParam("to") LocalDate to,
                               @QueryParam("userId") Long userId,
                               @QueryParam("projectId") Long projectId,
                               @QueryParam("billable") Boolean billable) {
        Membership me = access.team(orgId);
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "Pass a from and to date, with to on or after from");
        }
        boolean manager = me.role.canManage();
        Long user = manager ? userId : Long.valueOf(me.userId);
        if (!manager && userId != null && !Objects.equals(userId, me.userId)) {
            throw ApiException.forbidden("You can only export your own time");
        }

        StringBuilder query = new StringBuilder("organisationId = :org and entryDate between :from and :to and (startedAt is null or endedAt is not null)");
        Parameters params = Parameters.with("org", orgId).and("from", from).and("to", to);
        if (user != null) {
            query.append(" and userId = :user");
            params.and("user", user);
        }
        if (projectId != null) {
            query.append(" and projectId = :project");
            params.and("project", projectId);
        }
        if (billable != null) {
            query.append(" and billable = :billable");
            params.and("billable", billable);
        }
        query.append(" order by entryDate, userId, id");
        List<TimeEntry> entries = TimeEntry.list(query.toString(), params);

        RateBook rates = manager ? RateBook.load(orgId) : null;
        Map<Long, User> users = new HashMap<>();
        Map<Long, Project> projects = new HashMap<>();
        Map<Long, String> clients = new HashMap<>();
        Map<Long, String> tasks = new HashMap<>();
        Map<String, TimesheetStatus> statuses = new HashMap<>();

        List<String> header = new ArrayList<>(List.of("date", "week", "member", "email", "client", "project_code",
                "project", "task", "description", "hours", "billable", "timesheet_status"));
        if (manager) {
            header.addAll(List.of("rate", "amount"));
        }
        CsvWriter csv = new CsvWriter().row(header);
        for (TimeEntry e : entries) {
            User u = users.computeIfAbsent(e.userId, id -> User.findById(id));
            Project p = projects.computeIfAbsent(e.projectId, id -> Project.findById(id));
            String clientName = p.clientId == null ? "" : clients.computeIfAbsent(p.clientId, id -> {
                Client c = Client.findById(id);
                return c == null ? "" : c.name;
            });
            String taskTitle = e.taskId == null ? "" : tasks.computeIfAbsent(e.taskId, id -> {
                Task t = Task.findById(id);
                return t == null ? "" : t.title;
            });
            LocalDate week = WorkCalendar.weekStart(e.entryDate);
            TimesheetStatus status = statuses.computeIfAbsent(e.userId + ":" + week, k -> Timesheet.statusOf(orgId, e.userId, week));
            List<Object> row = new ArrayList<>(List.of(e.entryDate, week, u.name, u.email, clientName, p.code, p.name,
                    taskTitle, e.description == null ? "" : e.description, Money.hours(e.minutes),
                    e.billable ? "yes" : "no", status.name().toLowerCase()));
            if (manager) {
                BigDecimal rate = rates.rate(p.hourlyRate, e.userId);
                row.add(Money.round(rate));
                row.add(e.billable ? Money.cost(e.minutes, rate) : BigDecimal.ZERO.setScale(2));
            }
            csv.row(row);
        }
        String filename = "timesheets-" + from + "-to-" + to + ".csv";
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }
}
