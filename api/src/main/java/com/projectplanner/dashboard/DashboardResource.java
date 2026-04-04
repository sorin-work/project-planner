package com.projectplanner.dashboard;

import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectService;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.report.ProjectHealthService;
import com.projectplanner.report.ProjectHealthService.HealthSummary;
import com.projectplanner.project.ProjectDtos.ProjectResponse;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskDtos.TaskResponse;
import com.projectplanner.task.TaskMapper;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.time.TimeEntry;
import com.projectplanner.time.Timesheet;
import com.projectplanner.time.TimesheetStatus;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Path("/api/orgs/{orgId}/dashboard")
@Authenticated
public class DashboardResource {

    private static final int DUE_SOON_DAYS = 7;

    private final OrgAccess access;
    private final ProjectHealthService health;
    private final TaskMapper mapper;
    private final Clock clock;

    public DashboardResource(OrgAccess access, ProjectHealthService health, TaskMapper mapper, Clock clock) {
        this.access = access;
        this.health = health;
        this.mapper = mapper;
        this.clock = clock;
    }

    @GET
    public Dashboard get(@PathParam("orgId") long orgId) {
        Membership me = access.member(orgId);
        LocalDate today = Organisation.<Organisation>findById(orgId).today(clock);
        LocalDate weekStart = WorkCalendar.weekStart(today);

        List<Task> mine = Task.list("assigneeId = ?1 and status <> ?2 and projectId in "
                + "(select p.id from Project p where p.organisationId = ?3 and p.status <> ?4) "
                + "order by case when dueDate is null then 1 else 0 end, dueDate, id",
                me.userId, TaskStatus.DONE, orgId, ProjectStatus.DONE);
        List<Task> overdue = mine.stream().filter(t -> t.dueDate != null && t.dueDate.isBefore(today)).toList();
        List<Task> dueSoon = mine.stream()
                .filter(t -> t.dueDate != null && !t.dueDate.isBefore(today) && !t.dueDate.isAfter(today.plusDays(DUE_SOON_DAYS)))
                .toList();
        List<Task> inProgress = mine.stream().filter(t -> t.status == TaskStatus.IN_PROGRESS).toList();

        List<Project> projects = Project.<Project>list("organisationId = ?1 and status = ?2 order by name", orgId, ProjectStatus.ACTIVE)
                .stream()
                .filter(p -> me.role != Role.CLIENT || Objects.equals(p.clientId, me.clientId))
                .toList();
        List<ProjectCard> cards = projects.stream()
                .map(p -> new ProjectCard(ProjectService.toResponse(p, me.role.canManage()), health.summary(p, me)))
                .toList();

        long minutesThisWeek = 0;
        long billableThisWeek = 0;
        if (me.role.isTeam()) {
            for (TimeEntry e : TimeEntry.<TimeEntry>list("organisationId = ?1 and userId = ?2 and entryDate between ?3 and ?4",
                    orgId, me.userId, weekStart, weekStart.plusDays(6))) {
                minutesThisWeek += e.minutes;
                if (e.billable) {
                    billableThisWeek += e.minutes;
                }
            }
        }
        long pendingApprovals = me.role.canManage()
                ? Timesheet.count("organisationId = ?1 and status = ?2", orgId, TimesheetStatus.SUBMITTED)
                : 0;
        TimesheetStatus lastWeek = Timesheet.statusOf(orgId, me.userId, weekStart.minusWeeks(1));

        return new Dashboard(today, mapper.toResponses(overdue), mapper.toResponses(dueSoon),
                mapper.toResponses(inProgress), cards, minutesThisWeek, billableThisWeek, pendingApprovals,
                me.role.isTeam() ? lastWeek : null);
    }

    public record ProjectCard(ProjectResponse project, HealthSummary health) {
    }

    public record Dashboard(LocalDate today, List<TaskResponse> overdue, List<TaskResponse> dueSoon,
                            List<TaskResponse> inProgress, List<ProjectCard> activeProjects,
                            long minutesThisWeek, long billableMinutesThisWeek, long pendingApprovals,
                            TimesheetStatus lastWeekTimesheet) {
    }
}
