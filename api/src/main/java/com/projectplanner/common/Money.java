package com.projectplanner.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** Cost of {@code minutes} at an hourly {@code rate}, rounded to cents. */
    public static BigDecimal cost(long minutes, BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
