package com.projectplanner.org;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateResolverTest {

    private final RateResolver resolver = new RateResolver(new BigDecimal("60"),
            Map.of(Role.MANAGER, new BigDecimal("90"), Role.MEMBER, new BigDecimal("70")));

    @Test
    void projectRateWins() {
        assertThat(resolver.rateFor(new BigDecimal("120"), new BigDecimal("85"), Role.MEMBER)).isEqualByComparingTo("120");
    }

    @Test
    void memberRateBeatsRoleRate() {
        assertThat(resolver.rateFor(null, new BigDecimal("85"), Role.MEMBER)).isEqualByComparingTo("85");
    }

    @Test
    void roleRateWhenTheMemberHasNone() {
        assertThat(resolver.rateFor(null, null, Role.MANAGER)).isEqualByComparingTo("90");
    }

    @Test
    void organisationDefaultAsLastResort() {
        assertThat(resolver.rateFor(null, null, Role.OWNER)).isEqualByComparingTo("60");
        assertThat(resolver.rateFor(null, null, null)).isEqualByComparingTo("60");
    }

    @Test
    void missingDefaultIsZero() {
        assertThat(new RateResolver(null, Map.of()).rateFor(null, null, Role.MEMBER)).isEqualByComparingTo("0");
    }
}
