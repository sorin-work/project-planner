package com.projectplanner.report;

import com.projectplanner.report.BudgetThresholds.Step;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetThresholdsTest {

    @Test
    void thereAreTwoLevels() {
        assertThat(BudgetThresholds.level(0.0)).isZero();
        assertThat(BudgetThresholds.level(79.4)).isZero();
        assertThat(BudgetThresholds.level(80.0)).isEqualTo(80);
        assertThat(BudgetThresholds.level(99.4)).isEqualTo(80);
        assertThat(BudgetThresholds.level(100.0)).isEqualTo(100);
        assertThat(BudgetThresholds.level(240.0)).isEqualTo(100);
        // No budget, no burn.
        assertThat(BudgetThresholds.level(null)).isZero();
    }

    @Test
    void theBurnCountsAsTheOverviewShowsIt() {
        // 79.5 reads "80%" on the page, so that is when the alert goes out. 99.5 reads "100%".
        assertThat(BudgetThresholds.shown(79.5)).isEqualTo(80);
        assertThat(BudgetThresholds.level(79.5)).isEqualTo(80);
        assertThat(BudgetThresholds.level(99.5)).isEqualTo(100);
    }

    @Test
    void reachingAHigherLevelIsAnAlertOnce() {
        assertThat(BudgetThresholds.step(0, 70.0)).isEqualTo(new Step(0, false));
        assertThat(BudgetThresholds.step(0, 80.0)).isEqualTo(new Step(80, true));
        // More time above a level that was announced says nothing.
        assertThat(BudgetThresholds.step(80, 85.0)).isEqualTo(new Step(80, false));
        assertThat(BudgetThresholds.step(80, 101.0)).isEqualTo(new Step(100, true));
        assertThat(BudgetThresholds.step(100, 180.0)).isEqualTo(new Step(100, false));
    }

    @Test
    void goingStraightPastBothIsOneAlert() {
        assertThat(BudgetThresholds.step(0, 130.0)).isEqualTo(new Step(100, true));
    }

    @Test
    void aBurnThatFallsBackTakesItsLevelDownSoThatItCanBeAnnouncedAgain() {
        assertThat(BudgetThresholds.step(100, 90.0)).isEqualTo(new Step(80, false));
        assertThat(BudgetThresholds.step(80, 60.0)).isEqualTo(new Step(0, false));
        assertThat(BudgetThresholds.step(100, null)).isEqualTo(new Step(0, false));
        // And up again from there.
        assertThat(BudgetThresholds.step(0, 82.0)).isEqualTo(new Step(80, true));
    }
}
