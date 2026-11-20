package com.projectplanner.task;

import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.task.TaskDtos.CreateTaskRequest;
import com.projectplanner.task.TaskDtos.DependencyRequest;
import com.projectplanner.task.TaskDtos.DependencyResponse;
import com.projectplanner.task.TaskDtos.ShiftRequest;
import com.projectplanner.task.TaskDtos.TaskChange;
import com.projectplanner.task.TaskDtos.TaskResponse;
import com.projectplanner.task.TaskDtos.UpdateTaskRequest;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

@Path("/api")
@Authenticated
@Tag(name = "Tasks")
public class TaskResource {

    private final OrgAccess access;
    private final TaskService tasks;
    private final DependencyService dependencies;
    private final TaskMapper mapper;

    public TaskResource(OrgAccess access, TaskService tasks, DependencyService dependencies, TaskMapper mapper) {
        this.access = access;
        this.tasks = tasks;
        this.dependencies = dependencies;
        this.mapper = mapper;
    }

    /** Every task in the project, subtasks included (they carry a parentId). */
    @GET
    @Path("/projects/{projectId}/tasks")
    @Operation(summary = "List the tasks of a project",
            description = "Subtasks are in the list too. They carry a parentId.")
    public List<TaskResponse> list(@PathParam("projectId") long projectId) {
        access.project(projectId);
        return mapper.toResponses(Task.forProject(projectId));
    }

    @POST
    @Path("/projects/{projectId}/tasks")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Create a task, a subtask or a milestone",
            description = "For the team: owners, managers and members, not client viewers.")
    @Fails(status = 400, code = "unknown_phase", when = "phaseId is not a phase of the project")
    @Fails(status = 400, code = "unknown_task", when = "parentId is not a task of the project")
    @Fails(status = 400, code = "nested_subtask", when = "the parent is a subtask itself")
    @Fails(status = 400, code = "invalid_dates", when = "dueDate is before startDate")
    @Fails(status = 400, code = "unknown_assignee", when = "assigneeId is not on the team")
    public TaskResponse create(@PathParam("projectId") long projectId, @Valid @NotNull CreateTaskRequest request) {
        Project project = access.project(projectId);
        Membership me = access.editProject(project);
        return mapper.toResponse(tasks.create(me, project, request));
    }

    @GET
    @Path("/tasks/{taskId}")
    @Operation(summary = "Get a task")
    public TaskResponse get(@PathParam("taskId") long taskId) {
        return mapper.toResponse(find(taskId));
    }

    @GET
    @Path("/tasks/{taskId}/subtasks")
    @Operation(summary = "List the subtasks of a task")
    public List<TaskResponse> subtasks(@PathParam("taskId") long taskId) {
        Task task = find(taskId);
        return mapper.toResponses(Task.list("parentId = ?1 order by position, id", task.id));
    }

    @PATCH
    @Path("/tasks/{taskId}")
    @Transactional
    @Operation(summary = "Change a task",
            description = "For the team: owners, managers and members, not client viewers. Only the fields that are sent change. When the dates change, the tasks that depend on this one and would now start too early are pushed back, and come back in rescheduled.")
    @Fails(status = 400, code = "unknown_phase", when = "phaseId is not a phase of the project")
    @Fails(status = 400, code = "invalid_dates", when = "dueDate would be before startDate")
    @Fails(status = 400, code = "unknown_assignee", when = "assigneeId is not on the team")
    public TaskChange update(@PathParam("taskId") long taskId, @Valid @NotNull UpdateTaskRequest request) {
        Task task = find(taskId);
        Project project = Project.findById(task.projectId);
        Membership me = access.editProject(project);
        return tasks.update(me, project, task, request);
    }

    @POST
    @Path("/tasks/{taskId}/shift")
    @Transactional
    @Operation(summary = "Move a task by a number of working days, keeping its length",
            description = "For the team: owners, managers and members, not client viewers. A negative number moves the task earlier. Its length stays the same in working days. Dependants are pushed back as with a change of dates.")
    @Fails(status = 400, code = "unscheduled", when = "the task has no dates")
    public TaskChange shift(@PathParam("taskId") long taskId, @Valid @NotNull ShiftRequest request) {
        Task task = find(taskId);
        Project project = Project.findById(task.projectId);
        Membership me = access.editProject(project);
        return tasks.shift(me, project, task, request.days());
    }

    @DELETE
    @Path("/tasks/{taskId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Delete a task",
            description = "For the team: owners, managers and members, not client viewers.")
    public void delete(@PathParam("taskId") long taskId) {
        Task task = find(taskId);
        Project project = Project.findById(task.projectId);
        Membership me = access.editProject(project);
        tasks.delete(me, project, task);
    }

    @GET
    @Path("/projects/{projectId}/dependencies")
    @Tag(name = "Dependencies")
    @Operation(summary = "List the dependencies between the tasks of a project")
    public List<DependencyResponse> projectDependencies(@PathParam("projectId") long projectId) {
        access.project(projectId);
        return TaskDependency.forProject(projectId).stream().map(DependencyResponse::of).toList();
    }

    @POST
    @Path("/tasks/{taskId}/dependencies")
    @Transactional
    @ResponseStatus(201)
    @Tag(name = "Dependencies")
    @Operation(summary = "Make a task wait for another one",
            description = "For the team: owners, managers and members, not client viewers. The task in the path is the successor. If it now starts too early it is pushed back, together with its own dependants.")
    @Fails(status = 400, code = "unknown_task", when = "predecessorId is not a task of the same project")
    @Fails(status = 409, code = "dependency_exists", when = "the task already waits for that one")
    @Fails(status = 409, code = "dependency_cycle", when = "the link would close a loop. The message names the chain")
    public TaskChange addDependency(@PathParam("taskId") long taskId, @Valid @NotNull DependencyRequest request) {
        Task successor = find(taskId);
        Project project = Project.findById(successor.projectId);
        Membership me = access.editProject(project);
        return dependencies.add(me, project, successor, request);
    }

    @DELETE
    @Path("/dependencies/{dependencyId}")
    @Transactional
    @ResponseStatus(204)
    @Tag(name = "Dependencies")
    @Operation(summary = "Remove a dependency",
            description = "For the team: owners, managers and members, not client viewers.")
    public void removeDependency(@PathParam("dependencyId") long dependencyId) {
        TaskDependency dependency = TaskDependency.findById(dependencyId);
        if (dependency == null) {
            throw ApiException.notFound("Dependency");
        }
        Task successor = find(dependency.successorId);
        Project project = Project.findById(successor.projectId);
        Membership me = access.editProject(project);
        dependencies.remove(me, project, dependency);
    }

    private Task find(long taskId) {
        Task task = Task.findById(taskId);
        if (task == null) {
            throw ApiException.notFound("Task");
        }
        access.project(task.projectId);
        return task;
    }
}
