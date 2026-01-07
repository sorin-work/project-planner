package com.projectplanner.org;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Rates for everyone in an organisation, loaded once per request. */
public final class RateBook {

    private final RateResolver resolver;
    private final Map<Long, Membership> members = new HashMap<>();

    private RateBook(RateResolver resolver) {
        this.resolver = resolver;
    }

    public static RateBook load(long organisationId) {
        Organisation org = Organisation.findById(organisationId);
        RateBook book = new RateBook(new RateResolver(org.defaultHourlyRate, RoleRate.forOrganisation(organisationId)));
        Membership.forOrganisation(organisationId).forEach(m -> book.members.put(m.userId, m));
        return book;
    }

    public BigDecimal rate(BigDecimal projectRate, long userId) {
        Membership membership = members.get(userId);
        return resolver.rateFor(projectRate, membership == null ? null : membership.hourlyRate,
                membership == null ? null : membership.role);
    }
}
