package com.projectplanner.report;

import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.BudgetCalculator.TimeFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetCalculatorTest {

    private final BudgetCalculator calculator = new BudgetCalculator();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void burnsHoursAndMoney() {
        Budget b = calculator.calculate(bd("100"), bd("10000"), List.of(
                new TimeFacts(600, true, bd("80")),     // 10h, 800
                new TimeFacts(1200, true, bd("100")),   // 20h, 2000
                new TimeFacts(300, false, bd("100")))); // 5h, not billed
        assertThat(b.loggedHours()).isEqualByComparingTo("35");
        assertThat(b.billableHours()).isEqualByComparingTo("30");
        assertThat(b.nonBillableHours()).isEqualByComparingTo("5");
        assertThat(b.remainingHours()).isEqualByComparingTo("65");
        assertThat(b.hoursBurnPct()).isEqualTo(35.0);
        assertThat(b.spentAmount()).isEqualByComparingTo("2800");
        assertThat(b.remainingAmount()).isEqualByComparingTo("7200");
        assertThat(b.amountBurnPct()).isEqualTo(28.0);
    }

    @Test
    void roundsPartialHoursToCents() {
        Budget b = calculator.calculate(null, bd("1000"), List.of(new TimeFacts(50, true, bd("75"))));
        assertThat(b.spentAmount()).isEqualByComparingTo("62.50");
        assertThat(b.loggedHours()).isEqualByComparingTo("0.83");
    }

    @Test
    void roundsEveryEntryOnItsOwn() {
        // A minute at 100 an hour is 1.67. Three such entries are 5.01, three minutes in one entry are 5.00.
        Budget three = calculator.calculate(null, bd("100"), List.of(
                new TimeFacts(1, true, bd("100")), new TimeFacts(1, true, bd("100")), new TimeFacts(1, true, bd("100"))));
        Budget one = calculator.calculate(null, bd("100"), List.of(new TimeFacts(3, true, bd("100"))));
        assertThat(three.spentAmount()).isEqualByComparingTo("5.01");
        assertThat(one.spentAmount()).isEqualByComparingTo("5.00");
        assertThat(three.amountBurnPct()).isEqualTo(5.0);
    }

    @Test
    void entriesOfTheSameLengthCanComeAsOneWithACount() {
        // What the grouped query hands over: the same entries, counted instead of listed.
        Budget counted = calculator.calculate(bd("10"), bd("100"), List.of(
                new TimeFacts(1, true, bd("100"), 3), new TimeFacts(45, false, bd("100"), 2)));
        Budget listed = calculator.calculate(bd("10"), bd("100"), List.of(
                new TimeFacts(1, true, bd("100")), new TimeFacts(1, true, bd("100")), new TimeFacts(1, true, bd("100")),
                new TimeFacts(45, false, bd("100")), new TimeFacts(45, false, bd("100"))));
        assertThat(counted).isEqualTo(listed);
        assertThat(counted.spentAmount()).isEqualByComparingTo("5.01");
        assertThat(counted.loggedHours()).isEqualByComparingTo("1.55");
        assertThat(counted.billableHours()).isEqualByComparingTo("0.05");
    }

    @Test
    void noBudgetMeansNoBurnPercentage() {
        Budget b = calculator.calculate(null, null, List.of(new TimeFacts(60, true, bd("50"))));
        assertThat(b.hoursBurnPct()).isNull();
        assertThat(b.amountBurnPct()).isNull();
        assertThat(b.remainingHours()).isNull();
        assertThat(b.spentAmount()).isEqualByComparingTo("50");
    }

    @Test
    void zeroBudgetDoesNotDivideByZero() {
        Budget b = calculator.calculate(BigDecimal.ZERO, BigDecimal.ZERO, List.of(new TimeFacts(60, true, bd("50"))));
        assertThat(b.hoursBurnPct()).isNull();
        assertThat(b.amountBurnPct()).isNull();
    }

    @Test
    void overspendingShowsAsNegativeRemaining() {
        Budget b = calculator.calculate(bd("10"), bd("500"), List.of(new TimeFacts(720, true, bd("50"))));
        assertThat(b.remainingHours()).isEqualByComparingTo("-2");
        assertThat(b.hoursBurnPct()).isEqualTo(120.0);
        assertThat(b.remainingAmount()).isEqualByComparingTo("-100");
    }

    @Test
    void missingRateCostsNothing() {
        Budget b = calculator.calculate(null, bd("100"), List.of(new TimeFacts(60, true, null)));
        assertThat(b.spentAmount()).isEqualByComparingTo("0");
    }
}
