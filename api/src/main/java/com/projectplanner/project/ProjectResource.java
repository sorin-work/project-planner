package com.projectplanner.project;

import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import com.projectplanner.project.ProjectDtos.ProjectRequest;
import com.projectplanner.project.ProjectDtos.ProjectResponse;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Path("/api")
@Authenticated
public class ProjectResource {


    private final OrgAccess access;
    private final ProjectService projects;

    public ProjectResource(OrgAccess access, ProjectService projects) {
        this.access = access;
        this.projects = projects;
    }

    @GET
    @Path("/orgs/{orgId}/projects")
    public List<ProjectResponse> list(@PathParam("orgId") long orgId,
                                      @QueryParam("status") ProjectStatus status,
                                      @QueryParam("clientId") Long clientId) {
        Membership me = access.member(orgId);
        StringBuilder query = new StringBuilder("organisationId = :org");
        Parameters params = Parameters.with("org", orgId);
        if (me.role == Role.CLIENT) {
            query.append(" and clientId = :ownClient");
            params.and("ownClient", me.clientId == null ? -1L : me.clientId);
        }
        if (status != null) {
            query.append(" and status = :status");
            params.and("status", status);
        }
        if (clientId != null) {
            query.append(" and clientId = :client");
            params.and("client", clientId);
        }
        query.append(" order by name");
        List<Project> found = new ArrayList<>(Project.<Project>list(query.toString(), params));
        return found.stream().map(p -> ProjectService.toResponse(p, me.role.canManage())).toList();
    }

    @POST
    @Path("/orgs/{orgId}/projects")
    @Transactional
    @ResponseStatus(201)
    public ProjectResponse create(@PathParam("orgId") long orgId, @Valid @NotNull ProjectRequest request) {
        Membership me = access.manager(orgId);
        return ProjectService.toResponse(projects.create(me, request), true);
    }

    @GET
    @Path("/projects/{projectId}")
    public ProjectResponse get(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        return ProjectService.toResponse(project, access.member(project.organisationId).role.canManage());
    }

    @PUT
    @Path("/projects/{projectId}")
    @Transactional
    public ProjectResponse update(@PathParam("projectId") long projectId, @Valid @NotNull ProjectRequest request) {
        Project project = access.project(projectId);
        Membership me = access.manageProject(project);
        projects.update(me, project, request);
        return ProjectService.toResponse(project, true);
    }

    @DELETE
    @Path("/projects/{projectId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        access.manageProject(project);
        project.delete();
    }
}
