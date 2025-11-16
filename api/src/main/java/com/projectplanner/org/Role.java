package com.projectplanner.org;

/** Roles within an organisation, from most to least powerful. */
public enum Role {
    OWNER("owner"),
    MANAGER("manager"),
    MEMBER("member"),
    CLIENT("client viewer");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True if this role has at least the permissions of {@code other}. */
    public boolean atLeast(Role other) {
        return ordinal() <= other.ordinal();
    }

    /** Owners and managers run projects, see money and approve timesheets. */
    public boolean canManage() {
        return atLeast(MANAGER);
    }

    /** Everyone except client viewers works on tasks and logs time. */
    public boolean isTeam() {
        return this != CLIENT;
    }
}
