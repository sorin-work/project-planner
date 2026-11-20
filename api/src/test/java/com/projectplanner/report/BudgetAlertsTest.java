package com.projectplanner.report;

import com.projectplanner.project.Project;
import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.BudgetCalculator.TimeFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAlertsTest {

    private static Project project() {
        Project project = new Project();
        project.name = "Acme booking app";
        project.budgetHours = new BigDecimal("200");
        project.budgetAmount = new BigDecimal("20000.00");
        return project;
    }

    private static Budget budget(Project project, int minutes) {
        return new BudgetCalculator().calculate(project.budgetHours, project.budgetAmount,
                List.of(new TimeFacts(minutes, true, new BigDecimal("98.50"))));
    }

    @Test
    void theHoursMessageHasNoMoneyInIt() {
        Project project = project();
        String message = BudgetAlerts.hoursMessage(project, budget(project, 9870));

        assertThat(message).isEqualTo("Acme booking app has used 82% of its hours budget: 164.5 of 200 hours");
        // 16203.25 was spent. Neither that, nor the budget of 20000, nor a currency is in the text.
        assertThat(message).doesNotContain("16203", "20000", "EUR", "98.5");
    }

    @Test
    void theMoneyMessageSaysHowMuchOfWhat() {
        Project project = project();

        assertThat(BudgetAlerts.moneyMessage(project, budget(project, 9870), "EUR"))
                .isEqualTo("Acme booking app has used 81% of its money budget: 16203.25 of 20000.00 EUR");
        assertThat(BudgetAlerts.moneyMessage(project, budget(project, 13200), "USD"))
                .isEqualTo("Acme booking app has used 108% of its money budget: 21670.00 of 20000.00 USD");
    }

    @Test
    void wholeHoursAreWrittenWithoutDecimals() {
        Project project = project();

        assertThat(BudgetAlerts.hoursMessage(project, budget(project, 12600)))
                .isEqualTo("Acme booking app has used 105% of its hours budget: 210 of 200 hours");
    }
}
