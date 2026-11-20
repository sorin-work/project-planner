package com.projectplanner.report;

import com.projectplanner.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Budget burn for a project. All logged time counts against the hours budget;
 * only billable time, at each person's rate, counts against the money budget.
 * The overview, the health status and the budget alerts all take their numbers
 * from here.
 */
public final class BudgetCalculator {

    public Budget calculate(BigDecimal budgetHours, BigDecimal budgetAmount, Collection<TimeFacts> entries) {
        long totalMinutes = 0;
        long billableMinutes = 0;
        BigDecimal spent = BigDecimal.ZERO;
        for (TimeFacts entry : entries) {
            long minutes = entry.minutes() * entry.count();
            totalMinutes += minutes;
            if (entry.billable()) {
                billableMinutes += minutes;
                // One entry is priced and rounded to a cent, then counted as often as it occurs.
                spent = spent.add(Money.cost(entry.minutes(), entry.rate()).multiply(BigDecimal.valueOf(entry.count())));
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
        if (whole == null || whole.signum() <= 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Entries of the same length, billable flag and rate: {@code count} of them, each of
     * {@code minutes} minutes. Money is rounded per entry, so entries may only be put together
     * like this, never by adding up their minutes.
     */
    public record TimeFacts(long minutes, boolean billable, BigDecimal rate, long count) {

        public TimeFacts(long minutes, boolean billable, BigDecimal rate) {
            this(minutes, billable, rate, 1);
        }
    }

    public record Budget(BigDecimal budgetHours, BigDecimal loggedHours, BigDecimal billableHours,
                         BigDecimal nonBillableHours, BigDecimal remainingHours, Double hoursBurnPct,
                         BigDecimal budgetAmount, BigDecimal spentAmount, BigDecimal remainingAmount,
                         Double amountBurnPct) {
    }
}
