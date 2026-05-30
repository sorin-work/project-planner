package com.projectplanner.time;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import com.projectplanner.time.TimeDtos.StartTimerRequest;
import com.projectplanner.time.TimeDtos.TimeEntryRequest;
import com.projectplanner.time.TimeDtos.TimeEntryResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class TimeService {

    /** How far ahead people may log time, e.g. to book a planned day off against admin. */
    static final int MAX_DAYS_AHEAD = 7;

    private final Clock clock;
    private final ActivityLog activity;

    public TimeService(Clock clock, ActivityLog activity) {
        this.clock = clock;
        this.activity = activity;
    }

    public TimeEntry create(Membership me, TimeEntryRequest request) {
        Target target = target(me.organisationId, request.projectId(), request.taskId());
        checkDate(me.organisationId, request.date());
        checkUnlocked(me.organisationId, me.userId, request.date());
        TimeEntry entry = new TimeEntry();
        entry.organisationId = me.organisationId;
        entry.userId = me.userId;
        entry.projectId = target.project().id;
        entry.taskId = target.task() == null ? null : target.task().id;
        entry.entryDate = request.date();
        entry.minutes = request.minutes();
        entry.description = blankToNull(request.description());
        entry.billable = request.billable() != null ? request.billable() : defaultBillable(target.project());
        entry.createdAt = Instant.now(clock);
        entry.persist();
        return entry;
    }

    public void update(Membership me, TimeEntry entry, TimeEntryRequest request) {
        checkOwner(me, entry);
        checkUnlocked(entry.organisationId, entry.userId, entry.entryDate);
        Target target = target(entry.organisationId, request.projectId(), request.taskId());
        checkDate(entry.organisationId, request.date());
        checkUnlocked(entry.organisationId, entry.userId, request.date());
        entry.projectId = target.project().id;
        entry.taskId = target.task() == null ? null : target.task().id;
        entry.entryDate = request.date();
        if (!entry.isRunning()) {
            entry.minutes = request.minutes();
        }
        entry.description = blankToNull(request.description());
        if (request.billable() != null) {
            entry.billable = request.billable();
        }
    }

    public void delete(Membership me, TimeEntry entry) {
        checkOwner(me, entry);
        checkUnlocked(entry.organisationId, entry.userId, entry.entryDate);
        entry.delete();
    }

    /** Starts a timer, stopping (and saving) any timer that was already running. */
    public TimeEntry start(Membership me, StartTimerRequest request) {
        Target target = target(me.organisationId, request.projectId(), request.taskId());
        TimeEntry.runningFor(me.organisationId, me.userId).ifPresent(this::finish);
        Instant now = Instant.now(clock);
        LocalDate today = organisation(me.organisationId).today(clock);
        checkUnlocked(me.organisationId, me.userId, today);
        TimeEntry entry = new TimeEntry();
        entry.organisationId = me.organisationId;
        entry.userId = me.userId;
        entry.projectId = target.project().id;
        entry.taskId = target.task() == null ? null : target.task().id;
        entry.entryDate = today;
        entry.minutes = 0;
        entry.description = blankToNull(request.description());
        entry.billable = request.billable() != null ? request.billable() : defaultBillable(target.project());
        entry.startedAt = now;
        entry.createdAt = now;
        entry.persist();
        if (entry.taskId != null) {
            Task task = target.task();
            activity.record(me.organisationId, entry.projectId, task.id, me.userId, "timer.started",
                    User.<User>findById(me.userId).name + " started working on " + task.title);
        }
        return entry;
    }

    public Optional<TimeEntry> stop(Membership me) {
        Optional<TimeEntry> running = TimeEntry.runningFor(me.organisationId, me.userId);
        running.ifPresent(this::finish);
        return running;
    }

    /**
     * Ends a running timer. The time is booked on the day the timer was started,
     * in the organisation's time zone, even if it was stopped after midnight.
     */
    void finish(TimeEntry entry) {
        Instant now = Instant.now(clock);
        long minutes = Math.round(Duration.between(entry.startedAt, now).toSeconds() / 60.0);
        entry.endedAt = now;
        entry.minutes = (int) Math.max(1, Math.min(1440, minutes));
        entry.entryDate = LocalDate.ofInstant(entry.startedAt, organisation(entry.organisationId).zone());
    }

    public List<TimeEntryResponse> toResponses(Collection<TimeEntry> entries) {
        Set<Long> projectIds = new HashSet<>();
        Set<Long> taskIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (TimeEntry e : entries) {
            projectIds.add(e.projectId);
            userIds.add(e.userId);
            if (e.taskId != null) {
                taskIds.add(e.taskId);
            }
        }
        Map<Long, Project> projects = new HashMap<>();
        if (!projectIds.isEmpty()) {
            Project.<Project>list("id in ?1", projectIds).forEach(p -> projects.put(p.id, p));
        }
        Map<Long, String> tasks = new HashMap<>();
        if (!taskIds.isEmpty()) {
            Task.<Task>list("id in ?1", taskIds).forEach(t -> tasks.put(t.id, t.title));
        }
        Map<Long, String> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            User.<User>list("id in ?1", userIds).forEach(u -> users.put(u.id, u.name));
        }
        Map<String, TimesheetStatus> sheetStatus = new HashMap<>();
        return entries.stream().map(e -> {
            Project p = projects.get(e.projectId);
            LocalDate week = WorkCalendar.weekStart(e.entryDate);
            TimesheetStatus status = sheetStatus.computeIfAbsent(e.userId + ":" + week,
                    k -> Timesheet.statusOf(e.organisationId, e.userId, week));
            return new TimeEntryResponse(e.id, e.userId, users.get(e.userId), e.projectId, p.name, p.code, e.taskId,
                    e.taskId == null ? null : tasks.get(e.taskId), e.entryDate, e.minutes, e.description, e.billable,
                    e.startedAt, e.endedAt, e.isRunning(), status.locked());
        }).toList();
    }

    public TimeEntryResponse toResponse(TimeEntry entry) {
        return toResponses(List.of(entry)).getFirst();
    }

    private Target target(long organisationId, long projectId, Long taskId) {
        Project project = Project.findById(projectId);
        if (project == null || !Objects.equals(project.organisationId, organisationId)) {
            throw ApiException.badRequest("unknown_project", "Project not found in this organisation");
        }
        if (project.status == ProjectStatus.DONE) {
            throw ApiException.badRequest("project_done", project.name + " is finished; reopen it to log more time");
        }
        Task task = null;
        if (taskId != null) {
            task = Task.findById(taskId);
            if (task == null || !Objects.equals(task.projectId, project.id)) {
                throw ApiException.badRequest("unknown_task", "Task not found in this project");
            }
        }
        return new Target(project, task);
    }

    private void checkDate(long organisationId, LocalDate date) {
        LocalDate today = organisation(organisationId).today(clock);
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw ApiException.badRequest("date_in_future", "Time can be logged at most " + MAX_DAYS_AHEAD + " days ahead");
        }
    }

    static void checkUnlocked(long organisationId, long userId, LocalDate date) {
        LocalDate week = WorkCalendar.weekStart(date);
        TimesheetStatus status = Timesheet.statusOf(organisationId, userId, week);
        if (status.locked()) {
            throw ApiException.conflict("timesheet_locked",
                    "The timesheet for the week of " + week + " is " + status.name().toLowerCase() + " and can't be changed");
        }
    }

    private static void checkOwner(Membership me, TimeEntry entry) {
        if (!Objects.equals(entry.userId, me.userId) && !me.role.canManage()) {
            throw ApiException.forbidden("You can only change your own time");
        }
    }

    /** Client work is billable unless said otherwise; internal projects aren't. */
    static boolean defaultBillable(Project project) {
        return project.clientId != null;
    }

    private static Organisation organisation(long organisationId) {
        return Organisation.findById(organisationId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Target(Project project, Task task) {
    }
}
