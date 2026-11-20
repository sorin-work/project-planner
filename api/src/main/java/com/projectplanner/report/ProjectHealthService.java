package com.projectplanner.report;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ProjectHealthService {

    private final Clock clock;
    private final ProgressCalculator progressCalculator = new ProgressCalculator(WorkCalendar.DEFAULT);
    private final BudgetCalculator budgetCalculator = new BudgetCalculator();

    public ProjectHealthService(Clock clock) {
        this.clock = clock;
    }

    public ProjectHealth health(Project project, Membership viewer) {
        LocalDate today = Organisation.<Organisation>findById(project.organisationId).today(clock);
        List<Task> tasks = Task.forProject(project.id);
        Progress progress = progressCalculator.calculate(tasks.stream().map(ProjectHealthService::facts).toList(), today);
        Budget budget = budget(project);
        HealthStatus status = HealthStatus.of(progress, budget, project.status == ProjectStatus.DONE, started(project, tasks, today));

        List<OverdueTask> overdue = tasks.stream()
                .filter(t -> !t.isDone() && t.dueDate != null && t.dueDate.isBefore(today))
                .sorted(Comparator.comparing((Task t) -> t.dueDate).thenComparing(t -> t.id))
                .map(t -> new OverdueTask(t.id, t.title, t.dueDate, t.assigneeId,
                        WorkCalendar.DEFAULT.workingDaysBetween(t.dueDate.plusDays(1), today)))
                .toList();

        LocalDate plannedEnd = project.endDate;
        LocalDate forecastEnd = tasks.stream().map(t -> t.dueDate).filter(d -> d != null).max(Comparator.naturalOrder()).orElse(null);
        Integer endVarianceDays = plannedEnd == null || forecastEnd == null ? null
                : (forecastEnd.isAfter(plannedEnd)
                    ? WorkCalendar.DEFAULT.workingDaysBetween(plannedEnd.plusDays(1), forecastEnd)
                    : -WorkCalendar.DEFAULT.workingDaysBetween(forecastEnd.plusDays(1), plannedEnd));

        boolean money = viewer.role.canManage();
        BudgetView budgetView = new BudgetView(budget.budgetHours(), budget.loggedHours(), budget.billableHours(),
                budget.nonBillableHours(), budget.remainingHours(), budget.hoursBurnPct(),
                money ? budget.budgetAmount() : null, money ? budget.spentAmount() : null,
                money ? budget.remainingAmount() : null, money ? budget.amountBurnPct() : null);
        return new ProjectHealth(project.id, status, today, progress, budgetView, overdue, plannedEnd, forecastEnd, endVarianceDays);
    }

    /** A lighter version for project lists. */
    public HealthSummary summary(Project project, Membership viewer) {
        ProjectHealth h = health(project, viewer);
        return new HealthSummary(h.status(), h.progress().progressPct(), h.budget().hoursBurnPct(),
                h.budget().amountBurnPct(), h.progress().overdueTasks(), h.progress().openTasks(),
                h.progress().scheduleVarianceHours());
    }

    Budget budget(Project project) {
        RateBook rates = RateBook.load(project.organisationId);
        List<TimeFacts> entries = TimeEntry.<TimeEntry>list("projectId", project.id).stream()
                .map(e -> new TimeFacts(e.minutes, e.billable, rates.rate(project.hourlyRate, e.userId)))
                .toList();
        return budgetCalculator.calculate(project.budgetHours, project.budgetAmount, entries);
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
