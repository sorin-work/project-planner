package com.projectplanner.report;

import com.projectplanner.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Budget burn for a project. All logged time counts against the hours budget;
 * only billable time, at each person's rate, counts against the money budget.
 */
public final class BudgetCalculator {

    public Budget calculate(BigDecimal budgetHours, BigDecimal budgetAmount, Collection<TimeFacts> entries) {
        long totalMinutes = 0;
        long billableMinutes = 0;
        BigDecimal spent = BigDecimal.ZERO;
        for (TimeFacts entry : entries) {
            totalMinutes += entry.minutes();
            if (entry.billable()) {
                billableMinutes += entry.minutes();
                spent = spent.add(Money.cost(entry.minutes(), entry.rate()));
            }
        }
        BigDecimal logged = Money.hours(totalMinutes);
        return new Budget(
                budgetHours,
                logged,
                Money.hours(billableMinutes),
                Money.hours(totalMinutes - billableMinutes),
                budgetHours == null ? null : budgetHours.subtract(logged),
                percent(logged, budgetHours),
                budgetAmount,
                Money.round(spent),
                budgetAmount == null ? null : Money.round(budgetAmount.subtract(spent)),
                percent(spent, budgetAmount));
    }

    /** {@code part / whole} as a percentage, or null when there is no budget to compare against. */
    static Double percent(BigDecimal part, BigDecimal whole) {
        if (whole == null) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).doubleValue();
    }

    public record TimeFacts(long minutes, boolean billable, BigDecimal rate) {
    }

    public record Budget(BigDecimal budgetHours, BigDecimal loggedHours, BigDecimal billableHours,
                         BigDecimal nonBillableHours, BigDecimal remainingHours, Double hoursBurnPct,
                         BigDecimal budgetAmount, BigDecimal spentAmount, BigDecimal remainingAmount,
                         Double amountBurnPct) {
    }
}
