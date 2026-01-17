package com.projectplanner.report;

import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.ProgressCalculator.Progress;

/** A traffic light for a project, derived from schedule, budget and overdue work. */
public enum HealthStatus {
    ON_TRACK,
    AT_RISK,
    OFF_TRACK,
    NOT_STARTED,
    DONE;

    /** How far budget burn may run ahead of progress before it's a concern, in percentage points. */
    static final double BURN_AHEAD_TOLERANCE = 15;

    public static HealthStatus of(Progress progress, Budget budget, boolean projectDone, boolean started) {
        if (projectDone) {
            return DONE;
        }
        if (!started) {
            return NOT_STARTED;
        }
        Double burn = budget.hoursBurnPct() != null ? budget.hoursBurnPct() : budget.amountBurnPct();
        boolean overBudget = burn != null && burn > 100;
        boolean farBehind = progress.spi() != null && progress.spi() < 0.8;
        if (overBudget || farBehind || progress.overdueTasks() >= 5) {
            return OFF_TRACK;
        }
        boolean behind = progress.spi() != null && progress.spi() < 0.95;
        boolean burningFast = burn != null && burn > progress.progressPct() + BURN_AHEAD_TOLERANCE;
        if (behind || burningFast || progress.overdueTasks() > 0) {
            return AT_RISK;
        }
        return ON_TRACK;
    }
}
