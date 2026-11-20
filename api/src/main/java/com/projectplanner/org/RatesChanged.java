package com.projectplanner.org;

/**
 * Something that decides what people's time costs has changed in an
 * organisation: its default rate, a role's rate, or a member's rate or role.
 * Fired inside the transaction of the change. {@code membershipId} is the
 * member whose rate or role changed, when it was one member.
 */
public record RatesChanged(long organisationId, Long membershipId) {

    public RatesChanged(long organisationId) {
        this(organisationId, null);
    }
}
