package com.projectplanner.org;

import com.projectplanner.auth.CurrentUser;
import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.RequestScoped;


/**
 * Permission checks for the signed-in user. Organisations a user doesn't belong
 * to answer 404 rather than 403 so ids can't be probed.
 */
@RequestScoped
public class OrgAccess {

    private final CurrentUser currentUser;

    public OrgAccess(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public Membership member(long organisationId) {
        return Membership.find(organisationId, currentUser.id())
                .orElseThrow(() -> ApiException.notFound("Organisation"));
    }

    /** The membership if it has at least {@code role}, otherwise 403. */
    public Membership require(long organisationId, Role role) {
        Membership membership = member(organisationId);
        if (!membership.role.atLeast(role)) {
            throw ApiException.forbidden("This needs the " + role.label() + " role or higher");
        }
        return membership;
    }

    public Membership team(long organisationId) {
        return require(organisationId, Role.MEMBER);
    }

    public Membership manager(long organisationId) {
        return require(organisationId, Role.MANAGER);
    }

    public long userId() {
        return currentUser.id();
    }
}
