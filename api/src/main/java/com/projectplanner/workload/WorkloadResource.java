package com.projectplanner.workload;

import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Organisation;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.workload.WorkloadCalculator.Assignment;
import com.projectplanner.workload.WorkloadCalculator.MemberLoad;
import com.projectplanner.workload.WorkloadCalculator.Person;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api/orgs/{orgId}/workload")
@Authenticated
public class WorkloadResource {

    static final int MAX_WEEKS = 26;

    private final OrgAccess access;
    private final Clock clock;
    private final WorkloadCalculator calculator = new WorkloadCalculator(WorkCalendar.DEFAULT);

    public WorkloadResource(OrgAccess access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    /**
     * Allocated hours against weekly capacity for every team member. Only tasks in
     * planned and active projects count; on-hold work isn't booked.
     */
    @GET
    public WorkloadResponse get(@PathParam("orgId") long orgId,
                                @QueryParam("from") LocalDate from,
                                @QueryParam("weeks") @DefaultValue("8") int weeks,
                                @QueryParam("projectId") Long projectId) {
        access.team(orgId);
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw ApiException.badRequest("invalid_weeks", "Show between 1 and " + MAX_WEEKS + " weeks");
        }
        LocalDate start = WorkCalendar.weekStart(from != null ? from : Organisation.<Organisation>findById(orgId).today(clock));

        List<Membership> team = Membership.forOrganisation(orgId).stream().filter(m -> m.role.isTeam()).toList();
        Map<Long, User> users = new HashMap<>();
        team.forEach(m -> users.put(m.userId, User.findById(m.userId)));
        List<Person> people = team.stream().map(m -> new Person(m.userId, m.weeklyCapacityHours.doubleValue())).toList();

        List<Project> projects = Project.list("organisationId = ?1 and status in ?2", orgId,
                Set.of(ProjectStatus.PLANNED, ProjectStatus.ACTIVE));
        Map<Long, Project> projectsById = new HashMap<>();
        projects.forEach(p -> projectsById.put(p.id, p));
        if (projectId != null) {
            projectsById.keySet().retainAll(Set.of(projectId));
        }
        if (projectsById.isEmpty()) {
            return new WorkloadResponse(start, weeks, members(calculator.calculate(people, List.of(), start, weeks), users), List.of());
        }
        List<Task> tasks = Task.list("projectId in ?1 and assigneeId is not null and status <> ?2",
                projectsById.keySet(), TaskStatus.DONE);
        // A parent's estimate is the sum of its subtasks when it has any, so only book leaves.
        Set<Long> parents = new java.util.HashSet<>();
        tasks.forEach(t -> {
            if (t.parentId != null) {
                parents.add(t.parentId);
            }
        });
        List<Assignment> assignments = tasks.stream()
                .filter(t -> !parents.contains(t.id) && !t.milestone)
                .map(t -> new Assignment(t.id, t.assigneeId, t.estimate(), t.startDate, t.dueDate, t.isDone()))
                .toList();
        List<TaskRef> refs = tasks.stream()
                .map(t -> new TaskRef(t.id, t.title, t.projectId, projectsById.get(t.projectId).code,
                        projectsById.get(t.projectId).color, t.startDate, t.dueDate, t.estimate()))
                .toList();
        return new WorkloadResponse(start, weeks, members(calculator.calculate(people, assignments, start, weeks), users), refs);
    }

    private static List<MemberWorkload> members(List<MemberLoad> loads, Map<Long, User> users) {
        return loads.stream()
                .map(l -> new MemberWorkload(l.userId(), users.get(l.userId()).name, l.capacityHours(), l.weeks(), l.unscheduledHours()))
                .sorted(java.util.Comparator.comparing(MemberWorkload::name))
                .toList();
    }

    public record MemberWorkload(long userId, String name, double capacityHours,
                                 List<WorkloadCalculator.WeekLoad> weeks, double unscheduledHours) {
    }

    public record TaskRef(long id, String title, long projectId, String projectCode, String projectColor,
                          LocalDate startDate, LocalDate dueDate, double estimateHours) {
    }

    public record WorkloadResponse(LocalDate from, int weeks, List<MemberWorkload> members, List<TaskRef> tasks) {
    }
}
