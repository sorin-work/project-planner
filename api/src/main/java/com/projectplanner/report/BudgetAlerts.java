package com.projectplanner.report;

import com.projectplanner.common.Money;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.RateBook;
import com.projectplanner.org.RatesChanged;
import com.projectplanner.project.BudgetChanged;
import com.projectplanner.project.Project;
import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.BudgetThresholds.Step;
import com.projectplanner.time.TimeChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tells owners and managers when a project reaches 80 percent of a budget, and
 * when it reaches all of it. Hours and money are looked at separately and each
 * has its own message. See {@link BudgetThresholds} for when an alert goes out.
 *
 * <p>The look happens in the transaction that changed the time, the budget or a
 * rate, after the change, with the project's row locked by whoever made it. So
 * two changes at the same moment are looked at one after the other, and the
 * second finds the level the first one stored.
 */
@ApplicationScoped
public class BudgetAlerts {

    private final ProjectHealthService health;
    private final Notifier notifier;

    public BudgetAlerts(ProjectHealthService health, Notifier notifier) {
        this.health = health;
        this.notifier = notifier;
    }

    void timeChanged(@Observes TimeChanged event) {
        check(Project.findById(event.projectId()), null, true);
    }

    void budgetChanged(@Observes BudgetChanged event) {
        check(Project.findById(event.projectId()), null, true);
    }

    /** A rate moves the money burn of every project that has a money budget, without any time being logged. */
    void ratesChanged(@Observes RatesChanged event) {
        List<Project> projects = Project.<Project>find("organisationId = ?1 and budgetAmount is not null order by id",
                event.organisationId()).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        if (!projects.isEmpty()) {
            RateBook rates = RateBook.load(event.organisationId());
            projects.forEach(project -> check(project, rates, true));
        }
    }

    /** Looks at one project now. The caller has made sure nobody else is changing its time meanwhile. */
    public void check(Project project) {
        check(project, null, true);
    }

    /** Takes the levels of a project to where its burn is now, without telling anybody. For data that arrives with a history. */
    public void settle(Project project) {
        check(project, null, false);
    }

    private void check(Project project, RateBook rates, boolean tell) {
        if (project.budgetHours == null && project.budgetAmount == null && project.hoursAlertPct == 0 && project.amountAlertPct == 0) {
            // No budget and nothing to take back: not worth a single query.
            return;
        }
        boolean money = project.budgetAmount != null;
        Budget budget = health.budget(project, !money ? null : rates != null ? rates : RateBook.load(project.organisationId));
        Step hours = BudgetThresholds.step(project.hoursAlertPct, budget.hoursBurnPct());
        Step amount = BudgetThresholds.step(project.amountAlertPct, budget.amountBurnPct());
        project.hoursAlertPct = hours.level();
        project.amountAlertPct = amount.level();
        if (!tell || !(hours.alert() || amount.alert())) {
            return;
        }
        String aboutHours = hours.alert() ? hoursMessage(project, budget) : null;
        String aboutMoney = amount.alert()
                ? moneyMessage(project, budget, Organisation.<Organisation>findById(project.organisationId).currency) : null;
        for (Membership member : Membership.forOrganisation(project.organisationId)) {
            // Both go to the people who run projects and see their money. The two messages
            // are separate, and only the second has amounts in it, so that the first can go
            // to a wider audience one day without telling anybody what a project costs.
            if (!member.role.canManage()) {
                continue;
            }
            // No actor: the manager whose own entry crossed the line should hear about it too.
            if (aboutHours != null) {
                notifier.notify(member.userId, project.organisationId, null, NotificationType.BUDGET_ALERT, aboutHours, project.id, null);
            }
            if (aboutMoney != null) {
                notifier.notify(member.userId, project.organisationId, null, NotificationType.BUDGET_ALERT, aboutMoney, project.id, null);
            }
        }
    }

    /** Hours only: no amount, no rate, no currency. */
    static String hoursMessage(Project project, Budget budget) {
        return project.name + " has used " + BudgetThresholds.shown(budget.hoursBurnPct()) + "% of its hours budget: "
                + plain(budget.loggedHours()) + " of " + plain(budget.budgetHours()) + " hours";
    }

    static String moneyMessage(Project project, Budget budget, String currency) {
        return project.name + " has used " + BudgetThresholds.shown(budget.amountBurnPct()) + "% of its money budget: "
                + Money.round(budget.spentAmount()).toPlainString() + " of " + Money.round(budget.budgetAmount()).toPlainString()
                + " " + currency;
    }

    /** 164.50 as "164.5", 200.00 as "200". */
    private static String plain(BigDecimal hours) {
        BigDecimal stripped = hours.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
