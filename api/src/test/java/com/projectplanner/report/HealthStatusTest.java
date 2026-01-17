package com.projectplanner.report;

import com.projectplanner.report.BudgetCalculator.Budget;
import com.projectplanner.report.ProgressCalculator.Progress;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStatusTest {

    private static Progress progress(double pct, Double spi, int overdue) {
        return new Progress(100, pct, pct, pct, 0, 0, 0, spi, overdue, 5, 5);
    }

    private static Budget budget(Double hoursBurn) {
        return new Budget(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                hoursBurn, null, BigDecimal.ZERO, null, null);
    }

    @Test
    void doneAndNotStartedWin() {
        assertThat(HealthStatus.of(progress(10, 0.1, 9), budget(200.0), true, true)).isEqualTo(HealthStatus.DONE);
        assertThat(HealthStatus.of(progress(0, null, 0), budget(null), false, false)).isEqualTo(HealthStatus.NOT_STARTED);
    }

    @Test
    void onTrackWhenScheduleAndBudgetAreFine() {
        assertThat(HealthStatus.of(progress(50, 1.0, 0), budget(55.0), false, true)).isEqualTo(HealthStatus.ON_TRACK);
    }

    @Test
    void atRiskWhenSlightlyBehindOrBurningFast() {
        assertThat(HealthStatus.of(progress(50, 0.9, 0), budget(50.0), false, true)).isEqualTo(HealthStatus.AT_RISK);
        assertThat(HealthStatus.of(progress(40, 1.0, 0), budget(60.0), false, true)).isEqualTo(HealthStatus.AT_RISK);
        assertThat(HealthStatus.of(progress(40, 1.0, 1), budget(40.0), false, true)).isEqualTo(HealthStatus.AT_RISK);
    }

    @Test
    void offTrackWhenOverBudgetOrFarBehind() {
        assertThat(HealthStatus.of(progress(90, 1.0, 0), budget(101.0), false, true)).isEqualTo(HealthStatus.OFF_TRACK);
        assertThat(HealthStatus.of(progress(30, 0.7, 0), budget(30.0), false, true)).isEqualTo(HealthStatus.OFF_TRACK);
        assertThat(HealthStatus.of(progress(30, 1.0, 5), budget(30.0), false, true)).isEqualTo(HealthStatus.OFF_TRACK);
    }
}
