package com.projectplanner.project;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.calendar.OrgCalendars;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.project.ProjectDtos.DuplicateDefaults;
import com.projectplanner.project.ProjectDtos.DuplicateRequest;
import com.projectplanner.project.ProjectDtos.ProjectRequest;
import com.projectplanner.schedule.PlanShift;
import com.projectplanner.schedule.ScheduledTask;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.TaskDependency;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Copies the plan of a project into a new one: phases, tasks with their subtasks,
 * milestones and the links between them, moved to a new start. What happened in
 * the source stays there: logged time, comments, notifications, activity and who
 * finished what. Every copied task starts again as to do.
 *
 * <p>The caller holds the transaction, so a copy is there as a whole or not at all.
 *
 * <p>A plan can have thousands of tasks, and the copy must not cost a statement
 * per task. So the rows are not made one entity at a time. The dates, positions
 * and new parents are worked out here, and each table is then filled by one
 * {@code insert ... select} that takes the texts straight from the source rows and
 * the worked-out values from a list in the statement. The new ids are read back
 * by position, which the copy numbers anew anyway. The number of statements is
 * the same for ten tasks and for a few thousand.
 */
@ApplicationScoped
public class ProjectDuplicator {

    /**
     * Rows that go into one statement. Above it the rows are spread over more than
     * one, to keep a single statement to a few hundred kilobytes. Not final, so that
     * a test can lower it and reach the second statement without thousands of rows.
     */
    static int rowsPerStatement = 5000;

    private static final String COPY_SUFFIX = " (copy)";
    private static final int MAX_NAME = 120;

    private final ProjectService projects;
    private final ActivityLog activity;
    private final EntityManager em;
    private final Clock clock;
    /** The dates of a copy are moved in the working days of the organisation, holidays included. */
    private final OrgCalendars calendars;

    public ProjectDuplicator(ProjectService projects, ActivityLog activity, EntityManager em, Clock clock, OrgCalendars calendars) {
        this.projects = projects;
        this.activity = activity;
        this.em = em;
        this.clock = clock;
        this.calendars = calendars;
    }

    /** A name, a free code and a start date to offer for a copy of {@code source}. */
    public DuplicateDefaults defaults(Project source) {
        String stem = source.name.length() + COPY_SUFFIX.length() > MAX_NAME
                ? source.name.substring(0, MAX_NAME - COPY_SUFFIX.length()).stripTrailing() : source.name;
        List<String> taken = em.createQuery("select p.code from Project p where p.organisationId = ?1", String.class)
                .setParameter(1, source.organisationId).getResultList();
        Organisation organisation = Organisation.findById(source.organisationId);
        WorkCalendar calendar = calendars.of(source.organisationId);
        return new DuplicateDefaults(stem + COPY_SUFFIX, ProjectCodes.next(source.code, taken),
                calendar.onOrAfter(organisation.today(clock)),
                Phase.count("projectId", source.id),
                count("select count(t) from Task t where t.projectId = ?1", source.id),
                count("select count(d) from TaskDependency d, Task t where t.id = d.successorId and t.projectId = ?1", source.id));
    }

    public Project duplicate(Membership me, Project source, DuplicateRequest request) {
        List<Phase> phases = Phase.forProject(source.id);
        List<SourceTask> tasks = sourceTasks(source.id);
        List<TaskDependency> dependencies = TaskDependency.forProject(source.id);
        Set<Long> team = request.keepAssignees() ? teamOf(source.organisationId) : Set.of();

        // The first day of the plan lands on the day that was asked for, and everything else keeps its distance from it.
        WorkCalendar calendar = calendars.of(source.organisationId);
        LocalDate first = PlanShift.firstDay(calendar, tasks.stream().map(SourceTask::dates).toList(), source.startDate);
        PlanShift shift = first == null ? null : new PlanShift(calendar, first, request.startDate());

        Project copy = projects.insert(source.organisationId, new ProjectRequest(request.name(), request.code(),
                source.description, request.clientId(), ProjectStatus.PLANNED,
                newStart(calendar, source, shift, request.startDate()), newEnd(source, shift),
                source.budgetHours, source.budgetAmount, source.hourlyRate, source.color));
        em.flush();

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<Long, Long> newPhase = copyPhases(phases, copy.id, shift);
        Map<Long, Long> newTask = copyTasks(tasks, copy.id, newPhase, shift, team, now);
        int links = copyDependencies(dependencies, newTask, now);

        // The entry is read by everybody who sees the copy, and not all of them may see the source:
        // a copy can be made for another client. So the text does not name it. Its id is kept next
        // to the entry, and the name is added for the readers who may know it.
        activity.record(copy.organisationId, copy.id, null, me.userId, "project.duplicated",
                "Copied the plan from another project: " + count(phases.size(), "phase") + ", "
                        + count(tasks.size(), "task") + " and " + count(links, "link"), source.id);
        return copy;
    }

    /** The project's own start moves with the plan. A project where nothing has a date starts on the day that was asked for. */
    private static LocalDate newStart(WorkCalendar calendar, Project source, PlanShift shift, LocalDate asked) {
        return shift == null ? calendar.onOrAfter(asked) : shift.start(source.startDate);
    }

    private LocalDate newEnd(Project source, PlanShift shift) {
        if (shift == null) {
            // An end date and nothing else: there is no first day to measure it from.
            return null;
        }
        LocalDate start = shift.start(source.startDate);
        LocalDate end = shift.end(source.endDate);
        // A project that lies on days off entirely has its last working day before its first.
        return start != null && end != null && end.isBefore(start) ? start : end;
    }

    /** Phases in their order, numbered anew: the stored positions can have gaps and repeats. */
    private Map<Long, Long> copyPhases(List<Phase> phases, long projectId, PlanShift shift) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            rows.add(row(phase.id, i, date(shift == null ? phase.startDate : shift.start(phase.startDate)),
                    date(shift == null ? phase.endDate : shift.end(phase.endDate))));
        }
        insert("""
                insert into phases (project_id, name, position, start_date, end_date)
                select ?, s.name, m.position, cast(m.start_date as date), cast(m.end_date as date)
                from phases s join (values %s) as m (source_id, position, start_date, end_date) on m.source_id = s.id
                """, rows, projectId);
        Map<Integer, Long> created = idsByPosition("select id, position from phases where project_id = ?", projectId, phases.size());
        Map<Long, Long> newIds = new HashMap<>();
        for (int i = 0; i < phases.size(); i++) {
            newIds.put(phases.get(i).id, created.get(i));
        }
        return newIds;
    }

    /**
     * Tasks first, then their subtasks, which need the new id of their parent. Both
     * are numbered in the order of the source's list: everything is to do again, so
     * the old positions, which counted per board column, would collide.
     */
    private Map<Long, Long> copyTasks(List<SourceTask> tasks, long projectId, Map<Long, Long> newPhase, PlanShift shift,
                                      Set<Long> team, OffsetDateTime now) {
        Set<Long> ids = new HashSet<>();
        tasks.forEach(t -> ids.add(t.id()));
        List<SourceTask> parents = tasks.stream().filter(t -> t.parentId() == null || !ids.contains(t.parentId())).toList();
        List<SourceTask> subtasks = tasks.stream().filter(t -> t.parentId() != null && ids.contains(t.parentId())).toList();

        Map<Long, Long> newIds = new HashMap<>();
        Function<List<SourceTask>, List<String>> rows = batch -> {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                SourceTask task = batch.get(i);
                ScheduledTask dates = shift == null ? task.dates() : shift.task(task.dates());
                lines.add(row(task.id(), newPhase.get(task.phaseId()), newIds.get(task.parentId()), date(dates.start()),
                        date(dates.due()), task.assigneeId() != null && team.contains(task.assigneeId()) ? task.assigneeId() : null, i));
            }
            return lines;
        };
        String sql = """
                insert into tasks (project_id, phase_id, parent_id, title, description, status, priority, estimate_hours,
                                   start_date, due_date, assignee_id, milestone, position, created_at, updated_at)
                select ?, cast(m.phase_id as bigint), cast(m.parent_id as bigint), s.title, s.description, 'TODO', s.priority,
                       s.estimate_hours, cast(m.start_date as date), cast(m.due_date as date), cast(m.assignee_id as bigint),
                       s.milestone, m.position, ?, ?
                from tasks s join (values %s) as m (source_id, phase_id, parent_id, start_date, due_date, assignee_id, position)
                  on m.source_id = s.id
                """;

        insert(sql, rows.apply(parents), projectId, now, now);
        Map<Integer, Long> created = idsByPosition(
                "select id, position from tasks where project_id = ? and parent_id is null", projectId, parents.size());
        for (int i = 0; i < parents.size(); i++) {
            newIds.put(parents.get(i).id(), created.get(i));
        }
        if (!subtasks.isEmpty()) {
            insert(sql, rows.apply(subtasks), projectId, now, now);
            created = idsByPosition(
                    "select id, position from tasks where project_id = ? and parent_id is not null", projectId, subtasks.size());
            for (int i = 0; i < subtasks.size(); i++) {
                newIds.put(subtasks.get(i).id(), created.get(i));
            }
        }
        return newIds;
    }

    /** The links between copied tasks, with their lag. Returns how many there were. */
    private int copyDependencies(List<TaskDependency> dependencies, Map<Long, Long> newTask, OffsetDateTime now) {
        List<String> rows = new ArrayList<>();
        for (TaskDependency dependency : dependencies) {
            Long predecessor = newTask.get(dependency.predecessorId);
            Long successor = newTask.get(dependency.successorId);
            if (predecessor != null && successor != null) {
                rows.add(row(predecessor, successor, dependency.lagDays));
            }
        }
        insert("""
                insert into task_dependencies (predecessor_id, successor_id, lag_days, created_at)
                select m.predecessor_id, m.successor_id, m.lag_days, ?
                from (values %s) as m (predecessor_id, successor_id, lag_days)
                """, rows, now);
        return rows.size();
    }

    /** What the copy needs of a task. The texts are left in the database and copied there. */
    private record SourceTask(long id, Long parentId, Long phaseId, LocalDate start, LocalDate due, Long assigneeId) {
        ScheduledTask dates() {
            return new ScheduledTask(id, start, due);
        }
    }

    private List<SourceTask> sourceTasks(long projectId) {
        List<Object[]> rows = em.createQuery("""
                select t.id, t.parentId, t.phaseId, t.startDate, t.dueDate, t.assigneeId
                from Task t where t.projectId = ?1 order by t.position, t.id
                """, Object[].class).setParameter(1, projectId).getResultList();
        return rows.stream().map(r -> new SourceTask((Long) r[0], (Long) r[1], (Long) r[2], (LocalDate) r[3], (LocalDate) r[4], (Long) r[5])).toList();
    }

    /** People who can still be given a task. Someone who has left, or is a client viewer now, is not among them. */
    private Set<Long> teamOf(long organisationId) {
        Set<Long> team = new HashSet<>();
        for (Membership membership : Membership.forOrganisation(organisationId)) {
            if (membership.role.isTeam()) {
                team.add(membership.userId);
            }
        }
        return team;
    }

    private long count(String query, long projectId) {
        return em.createQuery(query, Long.class).setParameter(1, projectId).getSingleResult();
    }

    private static String count(int number, String what) {
        return number + " " + what + (number == 1 ? "" : "s");
    }

    // --- sql --------------------------------------------------------------------------------
    //
    // The rows go into the statement as text. They are ids and positions that this class
    // worked out and dates it formatted, never anything a person typed. What people type
    // (names, titles, descriptions) is copied inside the database and is not in the text.
    // The statements run on the session's own connection, in the caller's transaction, and
    // not through Hibernate's native queries: those keep every statement text they have
    // seen, and these are long and never the same twice.

    private static String row(Object... values) {
        StringBuilder row = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            row.append(i == 0 ? "" : ", ").append(values[i] == null ? "null" : values[i]);
        }
        return row.append(')').toString();
    }

    private static String date(LocalDate date) {
        if (date == null) {
            return null;
        }
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw ApiException.badRequest("date_out_of_range", "A date in this plan is too far away to copy: " + date);
        }
        return "date '" + date + "'";
    }

    private void insert(String sql, List<String> rows, Object... parameters) {
        int step = rowsPerStatement;
        for (int from = 0; from < rows.size(); from += step) {
            String values = String.join(", ", rows.subList(from, Math.min(rows.size(), from + step)));
            em.unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql.formatted(values))) {
                    for (int i = 0; i < parameters.length; i++) {
                        statement.setObject(i + 1, parameters[i]);
                    }
                    statement.executeUpdate();
                }
            });
        }
    }

    /** The ids of the rows just made, by the position each was given. */
    private Map<Integer, Long> idsByPosition(String sql, long projectId, int expected) {
        Map<Integer, Long> ids = new HashMap<>();
        if (expected == 0) {
            return ids;
        }
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        ids.put(result.getInt(2), result.getLong(1));
                    }
                }
            }
        });
        if (ids.size() != expected) {
            // The texts are copied from the source rows by a join. A row that somebody deleted
            // between reading the plan and copying it is missing here. Nothing is kept of the copy.
            throw ApiException.conflict("source_changed",
                    "The project was changed while it was being copied. Nothing was copied, so try again.");
        }
        return ids;
    }
}
