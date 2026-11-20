package com.projectplanner.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.calendar.OrgCalendars;
import com.projectplanner.common.ApiDocs;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.RateBook;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.BudgetCalculator.TimeFacts;
import com.projectplanner.report.ProgressCalculator.Progress;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.time.TimeEntry;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ProjectHealthService {

    private final Clock clock;
    private final OrgCalendars calendars;
    private final BudgetCalculator budgetCalculator = new BudgetCalculator();

    public ProjectHealthService(Clock clock, OrgCalendars calendars) {
        this.clock = clock;
        this.calendars = calendars;
    }

    public ProjectHealth health(Project project, Membership viewer) {
        LocalDate today = Organisation.<Organisation>findById(project.organisationId).today(clock);
        List<Task> tasks = Task.forProject(project.id);
        return health(project, viewer, calendars.of(project.organisationId), today, tasks,
                budget(project, RateBook.load(project.organisationId)));
    }

    /** Progress, overdue days and the end variance are counted in the organisation's working days. */
    private ProjectHealth health(Project project, Membership viewer, WorkCalendar calendar, LocalDate today, List<Task> tasks,
                                 Budget budget) {
        Progress progress = new ProgressCalculator(calendar).calculate(tasks.stream().map(ProjectHealthService::facts).toList(), today);
        HealthStatus status = HealthStatus.of(progress, budget, project.status == ProjectStatus.DONE, started(project, tasks, today));

        List<OverdueTask> overdue = tasks.stream()
                .filter(t -> !t.isDone() && t.dueDate != null && t.dueDate.isBefore(today))
                .sorted(Comparator.comparing((Task t) -> t.dueDate).thenComparing(t -> t.id))
                .map(t -> new OverdueTask(t.id, t.title, t.dueDate, t.assigneeId,
                        calendar.workingDaysBetween(t.dueDate.plusDays(1), today)))
                .toList();

        LocalDate plannedEnd = project.endDate;
        LocalDate forecastEnd = tasks.stream().map(t -> t.dueDate).filter(d -> d != null).max(Comparator.naturalOrder()).orElse(null);
        Integer endVarianceDays = plannedEnd == null || forecastEnd == null ? null
                : (forecastEnd.isAfter(plannedEnd)
                    ? calendar.workingDaysBetween(plannedEnd.plusDays(1), forecastEnd)
                    : -calendar.workingDaysBetween(forecastEnd.plusDays(1), plannedEnd));

        boolean money = viewer.role.canManage();
        BudgetView budgetView = new BudgetView(budget.budgetHours(), budget.loggedHours(), budget.billableHours(),
                budget.nonBillableHours(), budget.remainingHours(), budget.hoursBurnPct(),
                money ? budget.budgetAmount() : null, money ? budget.spentAmount() : null,
                money ? budget.remainingAmount() : null, money ? budget.amountBurnPct() : null);
        return new ProjectHealth(project.id, status, today, progress, budgetView, overdue, plannedEnd, forecastEnd, endVarianceDays);
    }

    /**
     * The summary of each project of one organisation, for a list, with a fixed number of
     * queries however many projects there are: the organisation, one for the tasks of all of
     * them, the rates once, the holidays once, and one grouped query for their time.
     */
    public Map<Long, HealthSummary> summaries(long organisationId, List<Project> projects, Membership viewer) {
        Map<Long, HealthSummary> summaries = new HashMap<>();
        if (projects.isEmpty()) {
            return summaries;
        }
        LocalDate today = Organisation.<Organisation>findById(organisationId).today(clock);
        List<Long> ids = projects.stream().map(p -> p.id).toList();
        Map<Long, List<Task>> tasks = new HashMap<>();
        Task.<Task>list("projectId in ?1 order by position, id", ids)
                .forEach(t -> tasks.computeIfAbsent(t.projectId, id -> new ArrayList<>()).add(t));
        Map<Long, Budget> budgets = budgets(projects, RateBook.load(organisationId));
        WorkCalendar calendar = calendars.of(organisationId);
        for (Project project : projects) {
            ProjectHealth h = health(project, viewer, calendar, today, tasks.getOrDefault(project.id, List.of()), budgets.get(project.id));
            summaries.put(project.id, summary(h));
        }
        return summaries;
    }

    private static HealthSummary summary(ProjectHealth h) {
        return new HealthSummary(h.status(), h.progress().progressPct(), h.budget().hoursBurnPct(),
                h.budget().amountBurnPct(), h.progress().overdueTasks(), h.progress().openTasks(),
                h.progress().scheduleVarianceHours());
    }

    /**
     * Hours and money burned on a project. The entries are grouped in the database by
     * person, billable flag and length, with a count: that is all the calculator needs, and
     * it keeps the rounding per entry. Without {@code rates} nothing has a price, which is
     * enough for a project without a money budget.
     */
    Budget budget(Project project, RateBook rates) {
        return budgets(List.of(project), rates).get(project.id);
    }

    /** The same for several projects of one organisation, in one grouped query. */
    Map<Long, Budget> budgets(List<Project> projects, RateBook rates) {
        if (projects.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TimeFacts>> entries = new HashMap<>();
        Map<Long, Project> byId = new HashMap<>();
        for (Project project : projects) {
            byId.put(project.id, project);
            entries.put(project.id, new ArrayList<>());
        }
        TimeEntry.getEntityManager()
                .createQuery("select e.projectId, e.userId, e.billable, e.minutes, count(e.id) from TimeEntry e"
                        + " where e.projectId in :projects group by e.projectId, e.userId, e.billable, e.minutes", Object[].class)
                .setParameter("projects", byId.keySet())
                .getResultList()
                .forEach(r -> {
                    Project project = byId.get((Long) r[0]);
                    entries.get(project.id).add(new TimeFacts(((Number) r[3]).longValue(), (Boolean) r[2],
                            rates == null ? null : rates.rate(project.hourlyRate, (Long) r[1]), ((Number) r[4]).longValue()));
                });
        Map<Long, Budget> budgets = new HashMap<>();
        for (Project project : projects) {
            budgets.put(project.id, budgetCalculator.calculate(project.budgetHours, project.budgetAmount, entries.get(project.id)));
        }
        return budgets;
    }

    private static boolean started(Project project, List<Task> tasks, LocalDate today) {
        if (project.status == ProjectStatus.ACTIVE || project.status == ProjectStatus.ON_HOLD) {
            return true;
        }
        if (project.startDate != null && !project.startDate.isAfter(today)) {
            return true;
        }
        return tasks.stream().anyMatch(t -> t.startDate != null && !t.startDate.isAfter(today));
    }

    static TaskFacts facts(Task t) {
        boolean started = t.status == TaskStatus.IN_PROGRESS || t.status == TaskStatus.IN_REVIEW;
        return new TaskFacts(t.id, t.parentId, t.estimate(), t.isDone(), started, t.startDate, t.dueDate, t.milestone);
    }

    public record OverdueTask(long id, String title, LocalDate dueDate, Long assigneeId, int daysOverdue) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Hours and money against the budgets. A figure that needs a budget is left out when the project has none.")
    public record BudgetView(BigDecimal budgetHours, BigDecimal loggedHours, BigDecimal billableHours,
                             BigDecimal nonBillableHours, BigDecimal remainingHours, Double hoursBurnPct,
                             @Schema(description = ApiDocs.MANAGERS_ONLY) BigDecimal budgetAmount,
                             @Schema(description = "Billable time at each person's rate. " + ApiDocs.MANAGERS_ONLY) BigDecimal spentAmount,
                             @Schema(description = ApiDocs.MANAGERS_ONLY) BigDecimal remainingAmount,
                             @Schema(description = ApiDocs.MANAGERS_ONLY) Double amountBurnPct) {
    }

    /**
     * @param endVarianceDays working days between the planned end and the latest task due date;
     *                        positive means the plan runs past the project end
     */
    public record ProjectHealth(long projectId, HealthStatus status, LocalDate asOf, Progress progress,
                                BudgetView budget, List<OverdueTask> overdue, LocalDate plannedEnd,
                                LocalDate forecastEnd, Integer endVarianceDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthSummary(HealthStatus status, double progressPct, Double hoursBurnPct,
                                @Schema(description = ApiDocs.MANAGERS_ONLY) Double amountBurnPct,
                                int overdueTasks, int openTasks, double scheduleVarianceHours) {
    }
}
