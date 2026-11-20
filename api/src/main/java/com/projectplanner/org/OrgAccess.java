package com.projectplanner.org;

import com.projectplanner.auth.CurrentUser;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.project.Project;
import com.projectplanner.time.TimeEntry;
import jakarta.enterprise.context.RequestScoped;

import java.util.Objects;

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
        Membership membership = member(project.organisationId);
        if (!sees(membership, project)) {
            throw ApiException.notFound("Project");
        }
        return membership;
    }

    /**
     * Whether somebody with this membership may know of the project at all. For a place that
     * mentions a project next to something else, and leaves the mention out instead of refusing.
     */
    public boolean sees(Membership membership, Project project) {
        return Objects.equals(membership.organisationId, project.organisationId)
                && (membership.role != Role.CLIENT || Objects.equals(membership.clientId, project.clientId));
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

    /**
     * A project named in a filter: it has to belong to this organisation and be one the
     * signed-in user can see. Anything else is a 404, so that a filter can't be used to find
     * out which projects exist.
     */
    public Project projectIn(long organisationId, long projectId) {
        Project project = Project.findById(projectId);
        if (project == null || !Objects.equals(project.organisationId, organisationId)) {
            throw ApiException.notFound("Project");
        }
        viewProject(project);
        return project;
    }

    /** A client named in a filter. A client viewer only finds their own. */
    public Client clientIn(long organisationId, long clientId) {
        Membership membership = member(organisationId);
        Client client = Client.findById(clientId);
        if (client == null || !Objects.equals(client.organisationId, organisationId)
                || (membership.role == Role.CLIENT && !Objects.equals(membership.clientId, clientId))) {
            throw ApiException.notFound("Client");
        }
        return client;
    }

    /**
     * Somebody whose time a filter asks for: a member of this organisation, or somebody who
     * was one and has time logged in it. Their time is still in the reports, so it has to be
     * possible to ask for it. An id that never had anything to do with the organisation is a 404.
     */
    public long personIn(long organisationId, long userId) {
        member(organisationId);
        if (Membership.find(organisationId, userId).isEmpty()
                && TimeEntry.count("organisationId = ?1 and userId = ?2", organisationId, userId) == 0) {
            throw ApiException.notFound("Member");
        }
        return userId;
    }

    public long userId() {
        return currentUser.id();
    }
}
