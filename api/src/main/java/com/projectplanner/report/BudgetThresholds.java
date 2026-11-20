package com.projectplanner.report;

/**
 * When people are told about a budget. There are two thresholds, the same for
 * hours and for money: 80 percent of the budget, and all of it.
 *
 * <p>The burn is compared the way the overview shows it, as a whole percentage,
 * so a project that reads "80%" there has had its alert. Each burn remembers the
 * highest threshold it has announced. Reaching a higher one is an alert, once.
 * More time above a threshold that was announced is not. A burn that falls back
 * (time deleted, the budget raised) takes its level down with it, so that
 * crossing the line again is an alert again.
 */
public final class BudgetThresholds {

    public static final int WARNING = 80;
    public static final int ALL = 100;

    private BudgetThresholds() {
    }

    /** What to remember from now on, and whether to tell people. */
    public record Step(int level, boolean alert) {
    }

    /** The burn as a whole percentage, as the overview shows it. */
    public static long shown(double burnPct) {
        return Math.round(burnPct);
    }

    /** The highest threshold a burn has reached: 0, 80 or 100. Without a budget there is no burn and no level. */
    public static int level(Double burnPct) {
        if (burnPct == null) {
            return 0;
        }
        long shown = shown(burnPct);
        return shown >= ALL ? ALL : shown >= WARNING ? WARNING : 0;
    }

    /**
     * From the level that was announced to the one the burn is at now. Going from under 80
     * straight past 100 is one alert, for 100.
     */
    public static Step step(int announced, Double burnPct) {
        int level = level(burnPct);
        return new Step(level, level > announced);
    }
}
