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
import jakarta.enterprise.event.Event;
import jakarta.persistence.LockModeType;

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

import static com.projectplanner.common.Text.blankToNull;

@ApplicationScoped
public class TimeService {

    /** How far ahead people may log time, e.g. to book a planned day off against admin. */
    static final int MAX_DAYS_AHEAD = 7;

    private final Clock clock;
    private final ActivityLog activity;
    /** Says that a project's time has changed, for whoever watches the total (the budget alerts). */
    private final Event<TimeChanged> changed;

    public TimeService(Clock clock, ActivityLog activity, Event<TimeChanged> changed) {
        this.clock = clock;
        this.activity = activity;
        this.changed = changed;
    }

    public TimeEntry create(Membership me, TimeEntryRequest request) {
        if (request.clientKey() != null) {
            Optional<TimeEntry> made = madeBefore(me, request);
            if (made.isPresent()) {
                return made.get();
            }
        }
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
        entry.clientKey = request.clientKey();
        entry.persist();
        changed.fire(new TimeChanged(entry.projectId));
        return entry;
    }

    /**
     * The entry an earlier create with the same key made, if there is one: the answer to the
     * first request may never have arrived, and the client asks again. The entry is returned
     * as it is now, also when the week has been locked or its time changed since. A key that
     * was used for another project, task or day is not a repeat and is refused.
     */
    private static Optional<TimeEntry> madeBefore(Membership me, TimeEntryRequest request) {
        // One keyed create at a time per person: a repeat that arrives while the first one is
        // still being stored waits here and then finds it.
        Membership.find("organisationId = ?1 and userId = ?2", me.organisationId, me.userId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list();
        Optional<TimeEntry> made = TimeEntry.madeWith(me.organisationId, me.userId, request.clientKey());
        made.ifPresent(entry -> {
            if (!Objects.equals(entry.projectId, request.projectId()) || !Objects.equals(entry.taskId, request.taskId())
                    || !Objects.equals(entry.entryDate, request.date())) {
                throw ApiException.conflict("client_key_reused", "This key was already used for another time entry");
            }
        });
        return made;
    }

    public void update(Membership me, TimeEntry entry, TimeEntryRequest request) {
        checkOwner(me, entry);
        checkUnlocked(entry.organisationId, entry.userId, entry.entryDate);
        long from = entry.projectId;
        lockInOrder(from, request.projectId());
        Target target = target(entry.organisationId, request.projectId(), request.taskId());
        if (!entry.isRunning()) {
            // Both the week the entry leaves and the one it moves into have to be open.
            checkDate(entry.organisationId, request.date());
            checkUnlocked(entry.organisationId, entry.userId, request.date());
            entry.entryDate = request.date();
            entry.minutes = request.minutes();
        }
        // A running timer keeps its day and has no length yet: both are set when it stops,
        // so neither can be moved here.
        entry.projectId = target.project().id;
        entry.taskId = target.task() == null ? null : target.task().id;
        entry.description = blankToNull(request.description());
        if (request.billable() != null) {
            entry.billable = request.billable();
        }
        changed.fire(new TimeChanged(entry.projectId));
        if (from != entry.projectId) {
            // Moved to another project: the one it left has less time now.
            changed.fire(new TimeChanged(from));
        }
    }

    public void delete(Membership me, TimeEntry entry) {
        checkOwner(me, entry);
        checkUnlocked(entry.organisationId, entry.userId, entry.entryDate);
        Project.locked(entry.projectId);
        entry.delete();
        changed.fire(new TimeChanged(entry.projectId));
    }

    /** Starts a timer, stopping (and saving) any timer that was already running. */
    public TimeEntry start(Membership me, StartTimerRequest request) {
        Optional<TimeEntry> running = TimeEntry.runningFor(me.organisationId, me.userId);
        running.ifPresent(timer -> lockInOrder(timer.projectId, request.projectId()));
        Target target = target(me.organisationId, request.projectId(), request.taskId());
        running.ifPresent(this::finish);
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
        LocalDate day = dayOf(entry);
        // Stopping books time like any other entry does, so that week has to be open.
        checkUnlocked(entry.organisationId, entry.userId, day);
        Project.locked(entry.projectId);
        long minutes = Math.round(Duration.between(entry.startedAt, now).toSeconds() / 60.0);
        entry.endedAt = now;
        entry.minutes = (int) Math.max(1, Math.min(1440, minutes));
        entry.entryDate = day;
        changed.fire(new TimeChanged(entry.projectId));
    }

    /** The day an entry counts for. For a running timer that is the day it was started, whatever its date says. */
    public LocalDate dayOf(TimeEntry entry) {
        return entry.isRunning()
                ? LocalDate.ofInstant(entry.startedAt, organisation(entry.organisationId).zone())
                : entry.entryDate;
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

    /**
     * The project and task an entry is for. The project's row is locked: changes to a
     * project's time happen one after the other, so that what is worked out from the total
     * afterwards sees every one of them.
     */
    private Target target(long organisationId, long projectId, Long taskId) {
        Project project = Project.locked(projectId);
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

    /** Locks two projects lowest id first, so that two changes that touch the same pair can't wait for each other. */
    private static void lockInOrder(long projectId, long otherProjectId) {
        if (projectId != otherProjectId) {
            Project.locked(Math.min(projectId, otherProjectId));
            Project.locked(Math.max(projectId, otherProjectId));
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

    private record Target(Project project, Task task) {
    }
}
