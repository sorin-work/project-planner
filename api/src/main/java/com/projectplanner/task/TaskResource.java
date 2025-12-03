package com.projectplanner.task;

import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.task.TaskDtos.CreateTaskRequest;
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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

@Path("/api")
@Authenticated
public class TaskResource {

    private final OrgAccess access;
    private final TaskService tasks;
    private final TaskMapper mapper;

    public TaskResource(OrgAccess access, TaskService tasks, TaskMapper mapper) {
        this.access = access;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /** Every task in the project, subtasks included (they carry a parentId). */
    @GET
    @Path("/projects/{projectId}/tasks")
    public List<TaskResponse> list(@PathParam("projectId") long projectId) {
        access.project(projectId);
        return mapper.toResponses(Task.forProject(projectId));
    }

    @POST
    @Path("/projects/{projectId}/tasks")
    @Transactional
    @ResponseStatus(201)
    public TaskResponse create(@PathParam("projectId") long projectId, @Valid @NotNull CreateTaskRequest request) {
        Project project = access.project(projectId);
        Membership me = access.editProject(project);
        return mapper.toResponse(tasks.create(me, project, request));
    }

    @GET
    @Path("/tasks/{taskId}")
    public TaskResponse get(@PathParam("taskId") long taskId) {
        return mapper.toResponse(find(taskId));
    }

    @PATCH
    @Path("/tasks/{taskId}")
    @Transactional
    public TaskResponse update(@PathParam("taskId") long taskId, @Valid @NotNull UpdateTaskRequest request) {
        Task task = find(taskId);
        Project project = Project.findById(task.projectId);
        Membership me = access.editProject(project);
        return mapper.toResponse(tasks.update(me, project, task, request));
    }

    @DELETE
    @Path("/tasks/{taskId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("taskId") long taskId) {
        Task task = find(taskId);
        Project project = Project.findById(task.projectId);
        Membership me = access.editProject(project);
        tasks.delete(me, project, task);
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
