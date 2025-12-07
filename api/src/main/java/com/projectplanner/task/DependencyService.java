package com.projectplanner.task;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.DependencyGraph;
import com.projectplanner.task.TaskDtos.DependencyRequest;
import com.projectplanner.task.TaskDtos.TaskResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class DependencyService {

    private final TaskMapper mapper;
    private final ActivityLog activity;
    private final Clock clock;

    public DependencyService(TaskMapper mapper, ActivityLog activity, Clock clock) {
        this.mapper = mapper;
        this.activity = activity;
        this.clock = clock;
    }

    /** Makes {@code successor} wait for the requested predecessor, unless that would create a loop. */
    public TaskResponse add(Membership me, Project project, Task successor, DependencyRequest request) {
        Task predecessor = TaskService.taskIn(project, request.predecessorId());
        if (TaskDependency.count("predecessorId = ?1 and successorId = ?2", predecessor.id, successor.id) > 0) {
            throw ApiException.conflict("dependency_exists", successor.title + " already depends on " + predecessor.title);
        }
        List<TaskDependency> existing = TaskDependency.forProject(project.id);
        List<Task> projectTasks = Task.forProject(project.id);
        DependencyGraph graph = new DependencyGraph(
                projectTasks.stream().map(t -> t.id).toList(),
                existing.stream().map(TaskDependency::toLink).toList());
        if (graph.wouldCreateCycle(predecessor.id, successor.id)) {
            throw ApiException.conflict("dependency_cycle", cycleMessage(graph, predecessor, successor, projectTasks));
        }

        TaskDependency dependency = new TaskDependency();
        dependency.predecessorId = predecessor.id;
        dependency.successorId = successor.id;
        dependency.lagDays = request.lagDays();
        dependency.createdAt = Instant.now(clock);
        dependency.persist();
        activity.record(project.organisationId, project.id, successor.id, me.userId, "dependency.added",
                successor.title + " now waits for " + predecessor.title);
        return mapper.toResponse(successor);
    }

    public void remove(Membership me, Project project, TaskDependency dependency) {
        Task predecessor = Task.findById(dependency.predecessorId);
        Task successor = Task.findById(dependency.successorId);
        dependency.delete();
        activity.record(project.organisationId, project.id, successor.id, me.userId, "dependency.removed",
                successor.title + " no longer waits for " + predecessor.title);
    }

    private static String cycleMessage(DependencyGraph graph, Task predecessor, Task successor, List<Task> projectTasks) {
        if (predecessor.id.equals(successor.id)) {
            return "A task can't depend on itself";
        }
        Map<Long, String> titles = new HashMap<>();
        projectTasks.forEach(t -> titles.put(t.id, t.title));
        String chain = graph.path(successor.id, predecessor.id).stream().map(titles::get).collect(Collectors.joining(" → "));
        return "That would create a loop: " + chain + " → " + successor.title;
    }
}
