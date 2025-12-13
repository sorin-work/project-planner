package com.projectplanner.task;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.Phase;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.Rescheduler;
import com.projectplanner.schedule.ScheduledTask;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.TaskDtos.CreateTaskRequest;
import com.projectplanner.task.TaskDtos.TaskChange;
import com.projectplanner.task.TaskDtos.UpdateTaskRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class TaskService {

    private final ActivityLog activity;
    private final TaskMapper mapper;
    private final Clock clock;
    private final Rescheduler rescheduler = new Rescheduler(WorkCalendar.DEFAULT);

    public TaskService(ActivityLog activity, TaskMapper mapper, Clock clock) {
        this.activity = activity;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Task create(Membership me, Project project, CreateTaskRequest request) {
        Instant now = Instant.now(clock);
        Task task = new Task();
        task.projectId = project.id;
        task.title = request.title().trim();
        task.description = blankToNull(request.description());
        task.phaseId = phaseIn(project, request.phaseId());
        if (request.parentId() != null) {
            Task parent = taskIn(project, request.parentId());
            if (parent.parentId != null) {
                throw ApiException.badRequest("nested_subtask", "Subtasks can't have subtasks of their own");
            }
            task.parentId = parent.id;
        }
        task.priority = request.priority() != null ? request.priority() : Priority.MEDIUM;
        task.estimateHours = request.estimateHours();
        task.milestone = request.milestone();
        setDates(task, request.startDate(), request.dueDate());
        task.assigneeId = assigneeIn(project, request.assigneeId());
        task.createdAt = now;
        setStatus(task, request.status() != null ? request.status() : TaskStatus.TODO, now);
        task.updatedAt = now;
        task.persist();

        activity.record(project.organisationId, project.id, task.id, me.userId, "task.created",
                "Created " + (task.milestone ? "milestone " : "task ") + task.title);
        return task;
    }

    public TaskChange update(Membership me, Project project, Task task, UpdateTaskRequest request) {
        Instant now = Instant.now(clock);
        LocalDate oldStart = task.startDate;
        LocalDate oldDue = task.dueDate;
        TaskStatus oldStatus = task.status;
        Long oldAssignee = task.assigneeId;

        if (request.title() != null) {
            task.title = request.title().trim();
        }
        if (request.description() != null) {
            task.description = blankToNull(request.description());
        }
        if (request.clearPhase()) {
            task.phaseId = null;
        } else if (request.phaseId() != null) {
            task.phaseId = phaseIn(project, request.phaseId());
        }
        if (request.priority() != null) {
            task.priority = request.priority();
        }
        if (request.estimateHours() != null) {
            task.estimateHours = request.estimateHours();
        }
        if (request.milestone() != null) {
            task.milestone = request.milestone();
        }
        if (request.clearDates()) {
            task.startDate = null;
            task.dueDate = null;
        } else if (request.startDate() != null || request.dueDate() != null || request.milestone() != null) {
            setDates(task,
                    request.startDate() != null ? request.startDate() : task.startDate,
                    request.dueDate() != null ? request.dueDate() : task.dueDate);
        }
        if (request.clearAssignee()) {
            task.assigneeId = null;
        } else if (request.assigneeId() != null) {
            task.assigneeId = assigneeIn(project, request.assigneeId());
        }
        if (request.status() != null) {
            setStatus(task, request.status(), now);
        }
        task.updatedAt = now;

        if (oldStatus != task.status) {
            activity.record(project.organisationId, project.id, task.id, me.userId, "task.status",
                    "Moved " + task.title + " to " + label(task.status));
        }
        if (!Objects.equals(oldAssignee, task.assigneeId) && task.assigneeId != null) {
            activity.record(project.organisationId, project.id, task.id, me.userId, "task.assigned",
                    "Assigned " + task.title + " to " + User.<User>findById(task.assigneeId).name);
        }
        boolean datesChanged = !Objects.equals(oldStart, task.startDate) || !Objects.equals(oldDue, task.dueDate);
        List<Task> moved = List.of();
        if (datesChanged) {
            activity.record(project.organisationId, project.id, task.id, me.userId, "task.rescheduled",
                    "Rescheduled " + task.title + " to " + describeDates(task));
            moved = reschedule(me, project, Set.of(task.id));
        }
        return new TaskChange(mapper.toResponse(task), mapper.toResponses(moved));
    }

    /** Moves a task by {@code days} calendar days, keeping its length, and pushes its dependants. */
    public TaskChange shift(Membership me, Project project, Task task, int days) {
        if (task.startDate == null && task.dueDate == null) {
            throw ApiException.badRequest("unscheduled", "Give the task dates before moving it on the timeline");
        }
        UpdateTaskRequest request = new UpdateTaskRequest(null, null, null, false, null, null, null,
                task.startDate == null ? null : task.startDate.plusDays(days),
                task.dueDate == null ? null : task.dueDate.plusDays(days),
                false, null, false, null);
        return update(me, project, task, request);
    }

    public void delete(Membership me, Project project, Task task) {
        activity.record(project.organisationId, project.id, null, me.userId, "task.deleted", "Deleted " + task.title);
        TaskDependency.delete("predecessorId = ?1 or successorId = ?1", task.id);
        for (Task subtask : Task.<Task>list("parentId", task.id)) {
            TaskDependency.delete("predecessorId = ?1 or successorId = ?1", subtask.id);
            subtask.delete();
        }
        task.delete();
    }

    /**
     * Applies the rescheduler to the project after {@code changed} moved and saves
     * the new dates. Returns the tasks that moved.
     */
    public List<Task> reschedule(Membership me, Project project, Set<Long> changed) {
        List<Task> tasks = Task.forProject(project.id);
        List<ScheduledTask> scheduled = tasks.stream().map(Task::toScheduled).toList();
        var links = TaskDependency.forProject(project.id).stream().map(TaskDependency::toLink).toList();
        Map<Long, ScheduledTask> shifted = rescheduler.propagate(scheduled, links, changed);
        if (shifted.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        List<Task> moved = new ArrayList<>();
        for (Task task : tasks) {
            ScheduledTask update = shifted.get(task.id);
            if (update != null) {
                task.startDate = update.start();
                task.dueDate = update.due();
                task.updatedAt = now;
                moved.add(task);
            }
        }
        moved.sort(Comparator.comparing((Task t) -> t.startDate).thenComparing(t -> t.id));
        activity.record(project.organisationId, project.id, null, me.userId, "task.cascade",
                moved.size() == 1 ? "Pushed back " + moved.getFirst().title + " to keep dependencies in order"
                        : "Pushed back " + moved.size() + " dependent tasks to keep dependencies in order");
        return moved;
    }

    private void setDates(Task task, LocalDate start, LocalDate due) {
        if (task.milestone) {
            LocalDate day = due != null ? due : start;
            task.startDate = day;
            task.dueDate = day;
            return;
        }
        if (start != null && due != null && due.isBefore(start)) {
            throw ApiException.badRequest("invalid_dates", "The due date can't be before the start date");
        }
        task.startDate = start;
        task.dueDate = due;
    }

    private static void setStatus(Task task, TaskStatus status, Instant now) {
        if (status == TaskStatus.DONE && task.completedAt == null) {
            task.completedAt = now;
        } else if (status != TaskStatus.DONE) {
            task.completedAt = null;
        }
        task.status = status;
    }

    static Long phaseIn(Project project, Long phaseId) {
        if (phaseId == null) {
            return null;
        }
        Phase phase = Phase.findById(phaseId);
        if (phase == null || !Objects.equals(phase.projectId, project.id)) {
            throw ApiException.badRequest("unknown_phase", "Phase not found in this project");
        }
        return phase.id;
    }

    static Task taskIn(Project project, long taskId) {
        Task task = Task.findById(taskId);
        if (task == null || !Objects.equals(task.projectId, project.id)) {
            throw ApiException.badRequest("unknown_task", "Task not found in this project");
        }
        return task;
    }

    static Long assigneeIn(Project project, Long userId) {
        if (userId == null) {
            return null;
        }
        Membership membership = Membership.find(project.organisationId, userId)
                .filter(m -> m.role.isTeam())
                .orElseThrow(() -> ApiException.badRequest("unknown_assignee", "Tasks can only be assigned to team members"));
        return membership.userId;
    }

    private static String describeDates(Task task) {
        if (task.startDate == null && task.dueDate == null) {
            return "no dates";
        }
        if (Objects.equals(task.startDate, task.dueDate) || task.startDate == null) {
            return String.valueOf(task.dueDate);
        }
        return task.startDate + " – " + (task.dueDate == null ? "?" : task.dueDate);
    }

    static String label(TaskStatus status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
