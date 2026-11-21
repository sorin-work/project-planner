package com.projectplanner.report;

import com.projectplanner.common.Money;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.RateBook;
import com.projectplanner.org.RatesChanged;
import com.projectplanner.org.Role;
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
import java.util.Map;

/**
 * Tells the people who run a project when it reaches 80 percent of a budget, and
 * when it reaches all of it: the owners and managers, or the lead and the owners
 * when it has a lead. Hours and money are looked at separately and each
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
        check(Project.findById(event.projectId()), true);
    }

    void budgetChanged(@Observes BudgetChanged event) {
        check(Project.findById(event.projectId()), true);
    }

    /**
     * A rate moves the money burn of every project that has a money budget, without any time
     * being logged. All of them are looked at with one grouped query.
     *
     * <p>Rows are locked in one order everywhere: memberships before projects, each by id. A
     * keyed time create locks the person's membership and then the project, so here the
     * membership that changed is written and locked before the first project is. Otherwise
     * the write would wait for the first query below, with the projects already held.
     */
    void ratesChanged(@Observes RatesChanged event) {
        Project.getEntityManager().flush();
        if (event.membershipId() != null) {
            Membership.findById(event.membershipId(), LockModeType.PESSIMISTIC_WRITE);
        }
        List<Project> projects = Project.<Project>find("organisationId = ?1 and budgetAmount is not null order by id",
                event.organisationId()).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        if (projects.isEmpty()) {
            return;
        }
        Map<Long, Budget> budgets = health.budgets(projects, RateBook.load(event.organisationId()));
        projects.forEach(project -> announce(project, budgets.get(project.id), true));
    }

    /** Looks at one project now. The caller has made sure nobody else is changing its time meanwhile. */
    public void check(Project project) {
        check(project, true);
    }

    /** Takes the levels of a project to where its burn is now, without telling anybody. For data that arrives with a history. */
    public void settle(Project project) {
        check(project, false);
    }

    private void check(Project project, boolean tell) {
        if (project.budgetHours == null && project.budgetAmount == null && project.hoursAlertPct == 0 && project.amountAlertPct == 0) {
            // No budget and nothing to take back: not worth a single query.
            return;
        }
        boolean money = project.budgetAmount != null;
        announce(project, health.budget(project, money ? RateBook.load(project.organisationId) : null), tell);
    }

    private void announce(Project project, Budget budget, boolean tell) {
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
        // No actor: the manager whose own entry crossed the line should hear about it too.
        List<Membership> members = Membership.forOrganisation(project.organisationId);
        if (aboutHours != null) {
            notifier.notifyAll(hearAboutHours(project, members), project.organisationId, null, NotificationType.BUDGET_ALERT,
                    aboutHours, project.id, null);
        }
        if (aboutMoney != null) {
            notifier.notifyAll(hearAboutMoney(project, members), project.organisationId, null, NotificationType.BUDGET_ALERT,
                    aboutMoney, project.id, null);
        }
    }

    /**
     * Who hears that a project used much of its hours. Without a lead, everybody who runs
     * projects: the owners and the managers. With one, the lead and the owners, and the other
     * managers no longer hear about a project that is not theirs. A member can lead a project,
     * and hears about its hours.
     */
    static List<Long> hearAboutHours(Project project, List<Membership> members) {
        return members.stream()
                .filter(m -> project.leadId == null ? m.role.canManage() : m.role == Role.OWNER || m.userId.equals(project.leadId))
                .map(m -> m.userId).toList();
    }

    /**
     * Who hears that a project used much of its money: the same people, but only those who
     * see amounts. A member who leads a project never gets this one, which is what the two
     * messages are kept apart for.
     */
    static List<Long> hearAboutMoney(Project project, List<Membership> members) {
        return members.stream()
                .filter(m -> m.role.canManage())
                .filter(m -> project.leadId == null || m.role == Role.OWNER || m.userId.equals(project.leadId))
                .map(m -> m.userId).toList();
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
