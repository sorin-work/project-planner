package com.projectplanner.project;

import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import com.projectplanner.project.ProjectDtos.DuplicateDefaults;
import com.projectplanner.project.ProjectDtos.DuplicateRequest;
import com.projectplanner.project.ProjectDtos.ProjectRequest;
import com.projectplanner.project.ProjectDtos.ProjectResponse;
import com.projectplanner.report.ProjectHealthService;
import com.projectplanner.report.ProjectHealthService.HealthSummary;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Path("/api")
@Authenticated
@Tag(name = "Projects")
public class ProjectResource {

    /** Active work first, finished projects last. */
    private static final List<ProjectStatus> LIST_ORDER =
            List.of(ProjectStatus.ACTIVE, ProjectStatus.PLANNED, ProjectStatus.ON_HOLD, ProjectStatus.DONE);

    private final OrgAccess access;
    private final ProjectService projects;
    private final ProjectHealthService health;
    private final ProjectDuplicator duplicator;

    public ProjectResource(OrgAccess access, ProjectService projects, ProjectHealthService health, ProjectDuplicator duplicator) {
        this.access = access;
        this.projects = projects;
        this.health = health;
        this.duplicator = duplicator;
    }

    @GET
    @Path("/orgs/{orgId}/projects")
    @Operation(summary = "List the projects, each with a summary of its health",
            description = "Active work first, finished projects last. A client viewer only gets the projects of their client.")
    public List<ProjectListItem> list(@PathParam("orgId") long orgId,
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
        found.sort(Comparator.comparingInt(p -> LIST_ORDER.indexOf(p.status)));
        List<ProjectListItem> items = new ArrayList<>();
        for (Project project : found) {
            items.add(new ProjectListItem(ProjectService.toResponse(project, me.role.canManage()),
                    health.summary(project, me)));
        }
        return items;
    }

    @POST
    @Path("/orgs/{orgId}/projects")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Create a project",
            description = "For owners and managers.")
    @Fails(status = 400, code = "invalid_dates", when = "endDate is before startDate")
    @Fails(status = 400, code = "unknown_client", when = "clientId is not a client of this organisation")
    @Fails(status = 409, code = "code_taken", when = "another project in the organisation already uses the code")
    public ProjectResponse create(@PathParam("orgId") long orgId, @Valid @NotNull ProjectRequest request) {
        Membership me = access.manager(orgId);
        return ProjectService.toResponse(projects.create(me, request), true);
    }

    @GET
    @Path("/projects/{projectId}")
    @Operation(summary = "Get a project")
    public ProjectResponse get(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        return ProjectService.toResponse(project, access.member(project.organisationId).role.canManage());
    }

    @PUT
    @Path("/projects/{projectId}")
    @Transactional
    @Operation(summary = "Change a project",
            description = "For owners and managers. The project is replaced: a field that is left out is cleared, apart from status and color.")
    @Fails(status = 400, code = "invalid_dates", when = "endDate is before startDate")
    @Fails(status = 400, code = "unknown_client", when = "clientId is not a client of this organisation")
    @Fails(status = 409, code = "code_taken", when = "another project in the organisation already uses the code")
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
    @Operation(summary = "Delete a project",
            description = "For owners and managers. Its phases, tasks and logged time are deleted with it.")
    public void delete(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        access.manageProject(project);
        project.delete();
    }

    /** What a copy of this project would be called by default: a name, a code that is free, and a start date. */
    @GET
    @Path("/projects/{projectId}/duplicate")
    public DuplicateDefaults duplicateDefaults(@PathParam("projectId") long projectId) {
        Project source = access.project(projectId);
        access.manageProject(source);
        return duplicator.defaults(source);
    }

    /**
     * Copies the plan of a project into a new, planned project that begins on the given
     * day. One transaction: the copy is there as a whole or not at all.
     */
    @POST
    @Path("/projects/{projectId}/duplicate")
    @Transactional
    @ResponseStatus(201)
    public ProjectResponse duplicate(@PathParam("projectId") long projectId, @Valid @NotNull DuplicateRequest request) {
        Project source = access.project(projectId);
        Membership me = access.manageProject(source);
        return ProjectService.toResponse(duplicator.duplicate(me, source, request), true);
    }

    public record ProjectListItem(ProjectResponse project, HealthSummary health) {
    }
}
