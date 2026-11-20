package com.projectplanner.task;

import com.projectplanner.calendar.OrgCalendars;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.CriticalPath;
import com.projectplanner.schedule.Link;
import com.projectplanner.schedule.ScheduleCheck;
import com.projectplanner.task.TaskDtos.ScheduleIssueResponse;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api")
@Authenticated
@Tag(name = "Planning")
public class ScheduleResource {

    private final OrgAccess access;
    private final OrgCalendars calendars;

    public ScheduleResource(OrgAccess access, OrgCalendars calendars) {
        this.access = access;
        this.calendars = calendars;
    }

    /** Critical path over every task in the project that has both a start and a due date. */
    @GET
    @Path("/projects/{projectId}/critical-path")
    @Operation(summary = "Critical path of a project",
            description = "Counts every task that has both a start and a due date.")
    public CriticalPath.Result critical(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        List<Task> tasks = Task.forProject(projectId);
        return new CriticalPath(calendars.of(project.organisationId)).calculate(tasks.stream().map(Task::toScheduled).toList(), links(projectId));
    }

    /**
     * Where the plan contradicts itself: tasks that start before the tasks they wait
     * for allow, links to tasks without dates, tasks due after the project's end and
     * subtasks outside their parent. Nothing is rejected when a task moves, so this is
     * where a broken dependency shows up.
     */
    @GET
    @Path("/projects/{projectId}/schedule-issues")
    @Operation(summary = "Where the plan of a project contradicts itself",
            description = "Tasks that start before the tasks they wait for allow, links to tasks without dates, tasks due after the end of the project and subtasks outside their parent. Nothing is rejected when a task moves, so this is where a broken dependency shows up. An issue that moving the task would fix comes with the dates to move it to.")
    public List<ScheduleIssueResponse> issues(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        List<Task> tasks = Task.forProject(projectId);
        Map<Long, Long> parents = new HashMap<>();
        Set<Long> done = new HashSet<>();
        for (Task task : tasks) {
            if (task.parentId != null) {
                parents.put(task.id, task.parentId);
            }
            if (task.isDone()) {
                done.add(task.id);
            }
        }
        return new ScheduleCheck(calendars.of(project.organisationId)).check(tasks.stream().map(Task::toScheduled).toList(), links(projectId), parents, done, project.endDate)
                .stream().map(ScheduleIssueResponse::of).toList();
    }

    private static List<Link> links(long projectId) {
        return TaskDependency.forProject(projectId).stream().map(TaskDependency::toLink).toList();
    }
}
