package com.projectplanner.task;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.DependencyGraph;
import com.projectplanner.schedule.DependencyType;
import com.projectplanner.task.TaskDtos.DependencyRequest;
import com.projectplanner.task.TaskDtos.TaskChange;
import com.projectplanner.task.TaskDtos.UpdateDependencyRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class DependencyService {

    private final TaskService tasks;
    private final TaskMapper mapper;
    private final ActivityLog activity;
    private final Clock clock;

    public DependencyService(TaskService tasks, TaskMapper mapper, ActivityLog activity, Clock clock) {
        this.tasks = tasks;
        this.mapper = mapper;
        this.activity = activity;
        this.clock = clock;
    }

    /**
     * Makes {@code successor} wait for the requested predecessor. Rejects links that
     * would create a loop, then pushes the successor (and its dependants) later if
     * they now start too early.
     */
    public TaskChange add(Membership me, Project project, Task successor, DependencyRequest request) {
        tasks.lockPlan(project);
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
        dependency.type = request.type() == null ? DependencyType.FS : request.type();
        dependency.createdAt = Instant.now(clock);
        dependency.persist();
        activity.record(project.organisationId, project.id, successor.id, me.userId, "dependency.added",
                successor.title + " now waits for " + predecessor.title);

        List<Task> moved = tasks.rescheduleFrom(me, project, successor.id);
        return new TaskChange(mapper.toResponse(successor), mapper.toResponses(moved));
    }

    /**
     * Changes the type or the lag of a link. The successor and its dependants are pushed
     * later if they now come too early. Nothing is pulled earlier: a link that asks for
     * less than before leaves the plan where it is, as moving a predecessor earlier does.
     */
    public TaskChange update(Membership me, Project project, TaskDependency dependency, UpdateDependencyRequest request) {
        tasks.lockPlan(project);
        Task predecessor = Task.findById(dependency.predecessorId);
        Task successor = Task.findById(dependency.successorId);
        boolean typeChanges = request.type() != null && request.type() != dependency.type;
        boolean lagChanges = request.lagDays() != null && request.lagDays() != dependency.lagDays;
        if (!typeChanges && !lagChanges) {
            // Nothing to record and nothing to move.
            return new TaskChange(mapper.toResponse(successor), List.of());
        }
        if (typeChanges) {
            dependency.type = request.type();
        }
        if (lagChanges) {
            dependency.lagDays = request.lagDays();
        }
        activity.record(project.organisationId, project.id, successor.id, me.userId, "dependency.changed",
                successor.title + " now waits for " + predecessor.title + " " + describe(dependency));
        List<Task> moved = tasks.rescheduleFrom(me, project, successor.id);
        return new TaskChange(mapper.toResponse(successor), mapper.toResponses(moved));
    }

    /** "finish to start, 2 working days later", for the activity log. */
    private static String describe(TaskDependency dependency) {
        String type = switch (dependency.type) {
            case FS -> "finish to start";
            case SS -> "start to start";
            case FF -> "finish to finish";
            case SF -> "start to finish";
        };
        int lag = dependency.lagDays;
        String days = Math.abs(lag) == 1 ? "1 working day" : Math.abs(lag) + " working days";
        return "(" + type + (lag == 0 ? "" : lag > 0 ? ", " + days + " later" : ", " + days + " earlier") + ")";
    }

    public void remove(Membership me, Project project, TaskDependency dependency) {
        tasks.lockPlan(project);
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
