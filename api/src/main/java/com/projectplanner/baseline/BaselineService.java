package com.projectplanner.baseline;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.baseline.BaselineDtos.BaselineRequest;
import com.projectplanner.baseline.BaselineDtos.BaselineResponse;
import com.projectplanner.baseline.BaselineDtos.ComparisonResponse;
import com.projectplanner.baseline.BaselineDtos.Counts;
import com.projectplanner.baseline.BaselineDtos.DateChangeResponse;
import com.projectplanner.baseline.BaselineDtos.PhaseChangeResponse;
import com.projectplanner.baseline.BaselineDtos.TaskChangeResponse;
import com.projectplanner.baseline.BaselineDtos.UpdateBaselineRequest;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.Phase;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.BaselineComparison;
import com.projectplanner.schedule.BaselineComparison.Change;
import com.projectplanner.schedule.BaselineComparison.Entry;
import com.projectplanner.schedule.WorkCalendar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class BaselineService {

    /** A project keeps this many baselines. One has to go before another can be saved. */
    public static final int MAX_PER_PROJECT = 10;

    private final ActivityLog activity;
    private final EntityManager em;
    private final Clock clock;
    // The organisation's own calendar belongs here once there is one (roadmap item 15).
    private final BaselineComparison comparison = new BaselineComparison(WorkCalendar.DEFAULT);

    public BaselineService(ActivityLog activity, EntityManager em, Clock clock) {
        this.activity = activity;
        this.em = em;
        this.clock = clock;
    }

    public List<BaselineResponse> list(Project project) {
        return toResponses(Baseline.forProject(project.id));
    }

    /**
     * Saves the plan's dates as they are now. The tasks and the phases are copied by one statement each
     * inside the database, whatever their number, and the caller holds the transaction,
     * so a baseline is the whole plan at one moment or it is not there.
     */
    public BaselineResponse take(Membership me, Project project, BaselineRequest request) {
        List<Baseline> existing = Baseline.forProject(project.id);
        if (existing.size() >= MAX_PER_PROJECT) {
            throw ApiException.conflict("baseline_limit",
                    "A project keeps up to " + MAX_PER_PROJECT + " baselines. Delete one to save another.");
        }
        Baseline baseline = new Baseline();
        baseline.projectId = project.id;
        baseline.name = freeName(existing, request.name(), null);
        baseline.projectStart = project.startDate;
        baseline.projectEnd = project.endDate;
        baseline.createdBy = me.userId;
        baseline.createdAt = Instant.now(clock);
        // The first baseline is the one to compare against until somebody says otherwise.
        baseline.reference = existing.isEmpty() || Boolean.TRUE.equals(request.reference());
        baseline.persist();
        em.flush();

        em.createNativeQuery("""
                insert into baseline_tasks (baseline_id, task_id, parent_task_id, phase_id, phase_name, title,
                                            start_date, due_date, estimate_hours, milestone)
                select ?1, t.id, t.parent_id, t.phase_id, p.name, t.title, t.start_date, t.due_date, t.estimate_hours, t.milestone
                from tasks t left join phases p on p.id = t.phase_id
                where t.project_id = ?2
                """).setParameter(1, baseline.id).setParameter(2, project.id).executeUpdate();
        em.createNativeQuery("""
                insert into baseline_phases (baseline_id, phase_id, name, start_date, end_date)
                select ?1, p.id, p.name, p.start_date, p.end_date from phases p where p.project_id = ?2
                """).setParameter(1, baseline.id).setParameter(2, project.id).executeUpdate();
        if (baseline.reference) {
            onlyReference(baseline);
        }
        activity.record(project.organisationId, project.id, null, me.userId, "baseline.saved", "Saved the baseline " + baseline.name);
        return toResponses(List.of(baseline)).getFirst();
    }

    /** Renames a baseline or makes it the reference. Nothing else about a baseline can change. */
    public BaselineResponse update(Baseline baseline, UpdateBaselineRequest request) {
        if (request.name() != null) {
            baseline.name = freeName(Baseline.forProject(baseline.projectId), request.name(), baseline.id);
        }
        if (Boolean.TRUE.equals(request.reference()) && !baseline.reference) {
            baseline.reference = true;
            em.flush();
            onlyReference(baseline);
        }
        return toResponses(List.of(baseline)).getFirst();
    }

    public void delete(Membership me, Project project, Baseline baseline) {
        boolean wasReference = baseline.reference;
        String name = baseline.name;
        baseline.delete();
        em.flush();
        if (wasReference) {
            // The comparison falls back to the latest one that is left.
            List<Baseline> left = Baseline.forProject(project.id);
            if (!left.isEmpty()) {
                left.getLast().reference = true;
            }
        }
        activity.record(project.organisationId, project.id, null, me.userId, "baseline.deleted", "Deleted the baseline " + name);
    }

    /** The plan as it is now against the baseline: per task, for the project's end and in total. */
    public ComparisonResponse compare(Project project, Baseline baseline) {
        List<BaselineTask> saved = BaselineTask.list("baselineId", baseline.id);
        List<Entry> then = saved.stream()
                .map(t -> new Entry(t.taskId, t.parentTaskId, t.title, t.startDate, t.dueDate, hours(t.estimateHours), t.milestone))
                .toList();
        List<Object[]> rows = em.createQuery("""
                select t.id, t.parentId, t.title, t.startDate, t.dueDate, t.estimateHours, t.milestone, p.name
                from Task t left join Phase p on p.id = t.phaseId
                where t.projectId = ?1
                """, Object[].class).setParameter(1, project.id).getResultList();
        List<Entry> now = new ArrayList<>();
        Map<Long, String> phases = new HashMap<>();
        // A task that is gone is listed under the phase it was in. One that is there, under the phase it is in now.
        saved.forEach(t -> phases.put(t.taskId, t.phaseName));
        for (Object[] row : rows) {
            now.add(new Entry((Long) row[0], (Long) row[1], (String) row[2], (LocalDate) row[3], (LocalDate) row[4],
                    hours((BigDecimal) row[5]), (Boolean) row[6]));
            phases.put((Long) row[0], (String) row[7]);
        }

        BaselineComparison.Result result = comparison.compare(then, now, baseline.projectEnd, project.endDate);
        List<TaskChangeResponse> tasks = result.tasks().stream()
                .map(t -> new TaskChangeResponse(t.taskId(), t.title(), t.milestone(), t.change(), t.existedThen(), t.existsNow(),
                        phases.get(t.taskId()), t.baselineStart(), t.baselineDue(), t.currentStart(), t.currentDue(),
                        t.startSlipDays(), t.finishSlipDays()))
                .toList();
        List<Entry> phasesThen = BaselinePhase.<BaselinePhase>list("baselineId", baseline.id).stream()
                .map(p -> new Entry(p.phaseId, null, p.name, p.startDate, p.endDate, 0, false)).toList();
        List<Entry> phasesNow = Phase.forProject(project.id).stream()
                .map(p -> new Entry(p.id, null, p.name, p.startDate, p.endDate, 0, false)).toList();
        List<PhaseChangeResponse> phaseChanges = comparison.comparePhases(phasesThen, phasesNow).stream()
                .map(p -> new PhaseChangeResponse(p.taskId(), p.title(), p.change(), p.existedThen(), p.existsNow(),
                        p.baselineStart(), p.baselineDue(), p.currentStart(), p.currentDue(), p.startSlipDays(), p.finishSlipDays()))
                .toList();
        Map<Change, Integer> counts = result.counts();
        return new ComparisonResponse(toResponses(List.of(baseline)).getFirst(), tasks, phaseChanges,
                DateChangeResponse.of(result.projectEnd()), DateChangeResponse.of(result.planEnd()),
                new Counts(counts.get(Change.UNCHANGED), counts.get(Change.SLIPPED), counts.get(Change.PULLED_IN),
                        counts.get(Change.ADDED), counts.get(Change.REMOVED)),
                result.baselineEstimateHours(), result.currentEstimateHours());
    }

    /** Two baselines with one name could not be told apart in a select. */
    private static String freeName(List<Baseline> inProject, String name, Long except) {
        String trimmed = name.trim();
        boolean taken = inProject.stream().anyMatch(b -> !Objects.equals(b.id, except) && b.name.equalsIgnoreCase(trimmed));
        if (taken) {
            throw ApiException.conflict("baseline_name_taken", "This project already has a baseline called " + trimmed);
        }
        return trimmed;
    }

    private void onlyReference(Baseline baseline) {
        em.createQuery("update Baseline b set b.reference = false where b.projectId = ?1 and b.id <> ?2")
                .setParameter(1, baseline.projectId).setParameter(2, baseline.id).executeUpdate();
    }

    private List<BaselineResponse> toResponses(List<Baseline> baselines) {
        if (baselines.isEmpty()) {
            return List.of();
        }
        List<Long> ids = baselines.stream().map(b -> b.id).toList();
        Map<Long, Long> tasks = new HashMap<>();
        em.createQuery("select t.baselineId, count(t) from BaselineTask t where t.baselineId in ?1 group by t.baselineId", Object[].class)
                .setParameter(1, ids).getResultList().forEach(row -> tasks.put((Long) row[0], (Long) row[1]));
        Set<Long> userIds = baselines.stream().map(b -> b.createdBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            User.<User>list("id in ?1", userIds).forEach(u -> names.put(u.id, u.name));
        }
        return baselines.stream().map(b -> new BaselineResponse(b.id, b.projectId, b.name, b.reference, b.projectStart, b.projectEnd,
                b.createdBy, b.createdBy == null ? null : names.get(b.createdBy), b.createdAt, tasks.getOrDefault(b.id, 0L))).toList();
    }

    private static double hours(BigDecimal estimate) {
        return estimate == null ? 0 : estimate.doubleValue();
    }
}
