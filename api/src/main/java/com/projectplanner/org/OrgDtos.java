package com.projectplanner.org;



public final class OrgDtos {

    private OrgDtos() {
    }

    public record MembershipSummary(long organisationId, String organisationName, Role role, String currency) {
        public static MembershipSummary of(Membership membership) {
            Organisation org = Organisation.findById(membership.organisationId);
            return new MembershipSummary(org.id, org.name, membership.role, org.currency);
        }
    }
}
