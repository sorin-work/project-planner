package com.projectplanner.org;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Works out the hourly rate for a person's time. The most specific rate wins:
 * the project's own rate, then the member's rate, then the rate for their role,
 * then the organisation's default.
 */
public final class RateResolver {

    private final BigDecimal organisationDefault;
    private final Map<Role, BigDecimal> roleRates;

    public RateResolver(BigDecimal organisationDefault, Map<Role, BigDecimal> roleRates) {
        this.organisationDefault = organisationDefault == null ? BigDecimal.ZERO : organisationDefault;
        this.roleRates = roleRates;
    }

    public BigDecimal rateFor(BigDecimal projectRate, BigDecimal memberRate, Role role) {
        if (projectRate != null) {
            return projectRate;
        }
        if (memberRate != null) {
            return memberRate;
        }
        BigDecimal byRole = role == null ? null : roleRates.get(role);
        return byRole != null ? byRole : organisationDefault;
    }

    public BigDecimal rateFor(Membership membership) {
        return rateFor(null, membership == null ? null : membership.hourlyRate, membership == null ? null : membership.role);
    }
}
