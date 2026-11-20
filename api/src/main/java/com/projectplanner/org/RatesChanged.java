package com.projectplanner.org;

/**
 * Something that decides what people's time costs has changed in an
 * organisation: its default rate, a role's rate, or a member's rate or role.
 * Fired inside the transaction of the change.
 */
public record RatesChanged(long organisationId) {
}
