package com.projectplanner.org;

import com.projectplanner.auth.CurrentUser;
import com.projectplanner.common.ApiException;
import com.projectplanner.project.Project;
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

    /** Read access to a project: the whole team, or a client viewer of the project's client. */
    public Membership viewProject(Project project) {
        return member(project.organisationId);
    }

    /** Planning changes (tasks, dates, dependencies) are for the team, not client viewers. */
    public Membership editProject(Project project) {
        Membership membership = viewProject(project);
        if (!membership.role.isTeam()) {
            throw ApiException.forbidden("Client viewers can't change the plan");
        }
        return membership;
    }

    public Membership manageProject(Project project) {
        Membership membership = viewProject(project);
        if (!membership.role.canManage()) {
            throw ApiException.forbidden("Only owners and managers can change project settings");
        }
        return membership;
    }

    public Project project(long projectId) {
        Project project = Project.findById(projectId);
        if (project == null) {
            throw ApiException.notFound("Project");
        }
        viewProject(project);
        return project;
    }

    public long userId() {
        return currentUser.id();
    }
}
