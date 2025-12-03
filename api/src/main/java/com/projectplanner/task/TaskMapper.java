package com.projectplanner.task;

import com.projectplanner.auth.User;
import com.projectplanner.task.TaskDtos.TaskResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds task responses in bulk so a project's task list costs a handful of queries. */
@ApplicationScoped
public class TaskMapper {

    public List<TaskResponse> toResponses(Collection<Task> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tasks.stream().map(t -> t.id).toList();


        Map<Long, Integer> subtasks = new HashMap<>();
        Task.<Task>list("parentId in ?1", ids).forEach(t -> subtasks.merge(t.parentId, 1, Integer::sum));


        Set<Long> assigneeIds = tasks.stream().map(t -> t.assigneeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!assigneeIds.isEmpty()) {
            User.<User>list("id in ?1", assigneeIds).forEach(u -> names.put(u.id, u.name));
        }

        return tasks.stream().map(t -> new TaskResponse(t.id, t.projectId, t.phaseId, t.parentId, t.title,
                t.description, t.status, t.priority, t.estimateHours, t.startDate, t.dueDate, t.assigneeId,
                t.assigneeId == null ? null : names.get(t.assigneeId), t.milestone,
                t.completedAt, t.createdAt, t.updatedAt,
                subtasks.getOrDefault(t.id, 0))).toList();
    }

    public TaskResponse toResponse(Task task) {
        return toResponses(List.of(task)).getFirst();
    }
}
