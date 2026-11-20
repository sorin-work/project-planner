package com.projectplanner.project;

import com.projectplanner.attachment.Attachment;
import com.projectplanner.attachment.AttachmentFiles;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Map;

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

    private final AttachmentFiles attachmentFiles;

    public ProjectResource(OrgAccess access, ProjectService projects, ProjectHealthService health, ProjectDuplicator duplicator,
                           AttachmentFiles attachmentFiles) {
        this.attachmentFiles = attachmentFiles;
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
        // A fixed number of queries for any number of projects: the clients, the tasks, the rates and the time all come at once.
        List<ProjectResponse> responses = ProjectService.toResponses(found, me.role.canManage());
        Map<Long, HealthSummary> summaries = health.summaries(orgId, found, me);
        List<ProjectListItem> items = new ArrayList<>();
        for (ProjectResponse response : responses) {
            items.add(new ProjectListItem(response, summaries.get(response.id())));
        }
        return items;
    }

    @GET
    @Path("/orgs/{orgId}/projects/options")
    @Operation(summary = "List the projects for a select",
            description = "Id, code, name, status and client of each project the caller can see, in the order of the full list. "
                    + "Nothing is worked out, so this is what a form asks for to fill a project select.")
    public List<ProjectOption> options(@PathParam("orgId") long orgId) {
        Membership me = access.member(orgId);
        List<Project> found = me.role == Role.CLIENT
                ? Project.list("organisationId = ?1 and clientId = ?2 order by name", orgId, me.clientId == null ? -1L : me.clientId)
                : Project.list("organisationId = ?1 order by name", orgId);
        // The same order as the full list: active work first, finished projects last, by name within.
        List<Project> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt(p -> LIST_ORDER.indexOf(p.status)));
        Map<Long, String> clients = ProjectService.clientNames(ordered);
        return ordered.stream()
                .map(p -> new ProjectOption(p.id, p.code, p.name, p.status, p.clientId, p.clientId == null ? null : clients.get(p.clientId)))
                .toList();
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
        // The rows go by the foreign keys; the files of the attachments go once the deletion is committed.
        attachmentFiles.deleteAfterCommit(Attachment.keysOfProject(project.id));
        project.delete();
    }

    /** What a copy of this project would be called by default: a name, a code that is free, and a start date. */
    @GET
    @Path("/projects/{projectId}/duplicate")
    @Operation(summary = "Propose a name, a code and a start date for a copy of a project",
            description = "For owners and managers. The code is free when it is proposed. The counts say how much there is to copy.")
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
    @Operation(summary = "Copy the plan of a project into a new project",
            description = "For owners and managers. Phases, tasks, subtasks, milestones and links are copied, with every date at "
                    + "the same distance in working days from the first day of the plan, which lands on startDate. The new project "
                    + "is planned and every task is to do. Logged time, comments, notifications and activity are not copied. "
                    + "Without clientId the copy is internal, and without keepAssignees nobody is assigned. The files attached to the tasks "
                    + "are only copied with copyAttachments.")
    @Fails(status = 400, code = "unknown_client", when = "clientId is not a client of this organisation")
    @Fails(status = 400, code = "date_out_of_range", when = "a date of the copied plan would lie outside the years 1 to 9999")
    @Fails(status = 409, code = "code_taken", when = "another project in the organisation already uses the code")
    @Fails(status = 409, code = "source_changed", when = "a task or phase of the source was deleted while it was being copied")
    @Fails(status = 413, code = "storage_full", when = "copyAttachments is set and the copied files do not fit in the organisation's limit")
    public ProjectResponse duplicate(@PathParam("projectId") long projectId, @Valid @NotNull DuplicateRequest request) {
        Project source = access.project(projectId);
        Membership me = access.manageProject(source);
        return ProjectService.toResponse(duplicator.duplicate(me, source, request), true);
    }

    public record ProjectListItem(ProjectResponse project, HealthSummary health) {
    }

    /** A project as a select needs it. The client fields are left out for an internal project. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectOption(long id, String code, String name, ProjectStatus status, Long clientId, String clientName) {
    }
}
