package com.projectplanner.task;

import com.projectplanner.org.OrgAccess;
import com.projectplanner.schedule.CriticalPath;
import com.projectplanner.schedule.WorkCalendar;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.List;

@Path("/api/projects/{projectId}/critical-path")
@Authenticated
public class ScheduleResource {

    private final OrgAccess access;
    private final CriticalPath criticalPath = new CriticalPath(WorkCalendar.DEFAULT);

    public ScheduleResource(OrgAccess access) {
        this.access = access;
    }

    /** Critical path over every task in the project that has both a start and a due date. */
    @GET
    public CriticalPath.Result get(@PathParam("projectId") long projectId) {
        access.project(projectId);
        List<Task> tasks = Task.forProject(projectId);
        var links = TaskDependency.forProject(projectId).stream().map(TaskDependency::toLink).toList();
        return criticalPath.calculate(tasks.stream().map(Task::toScheduled).toList(), links);
    }
}
