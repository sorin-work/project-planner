package com.projectplanner.common;

/** Sentences the API reference says about more than one field. */
public final class ApiDocs {

    /** For the money fields that {@code role.canManage()} decides about. */
    public static final String MANAGERS_ONLY = "Only for owners and managers. Left out of the answer for everyone else.";

    /** For the flags of a partial update that take a value away. */
    public static final String CLEARS = "Send true to remove the value. Leaving a field out, or sending null, keeps it.";

    private ApiDocs() {
    }
}
