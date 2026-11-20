package com.projectplanner.project;

import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.ProjectDtos.PhaseRequest;
import com.projectplanner.project.ProjectDtos.PhaseResponse;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

@Path("/api")
@Authenticated
@Tag(name = "Projects")
public class PhaseResource {

    private final OrgAccess access;

    public PhaseResource(OrgAccess access) {
        this.access = access;
    }

    @GET
    @Path("/projects/{projectId}/phases")
    @Operation(summary = "List the phases of a project")
    public List<PhaseResponse> list(@PathParam("projectId") long projectId) {
        access.project(projectId);
        return Phase.forProject(projectId).stream().map(PhaseResponse::of).toList();
    }

    @POST
    @Path("/projects/{projectId}/phases")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Add a phase",
            description = "For owners and managers.")
    @Fails(status = 400, code = "invalid_dates", when = "endDate is before startDate")
    public PhaseResponse create(@PathParam("projectId") long projectId, @Valid @NotNull PhaseRequest request) {
        Project project = access.project(projectId);
        access.manageProject(project);
        Phase phase = new Phase();
        phase.projectId = projectId;
        phase.position = request.position() != null ? request.position() : (int) Phase.count("projectId", projectId);
        apply(phase, request);
        phase.persist();
        return PhaseResponse.of(phase);
    }

    @PUT
    @Path("/phases/{phaseId}")
    @Transactional
    @Operation(summary = "Change a phase",
            description = "For owners and managers.")
    @Fails(status = 400, code = "invalid_dates", when = "endDate is before startDate")
    public PhaseResponse update(@PathParam("phaseId") long phaseId, @Valid @NotNull PhaseRequest request) {
        Phase phase = find(phaseId);
        if (request.position() != null) {
            phase.position = request.position();
        }
        apply(phase, request);
        return PhaseResponse.of(phase);
    }

    /** Tasks in the phase stay in the project, just without a phase. */
    @DELETE
    @Path("/phases/{phaseId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Delete a phase",
            description = "For owners and managers. Its tasks stay in the project, without a phase.")
    public void delete(@PathParam("phaseId") long phaseId) {
        find(phaseId).delete();
    }

    private Phase find(long phaseId) {
        Phase phase = Phase.findById(phaseId);
        if (phase == null) {
            throw ApiException.notFound("Phase");
        }
        access.manageProject(access.project(phase.projectId));
        return phase;
    }

    private static void apply(Phase phase, PhaseRequest request) {
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("invalid_dates", "The end date can't be before the start date");
        }
        phase.name = request.name().trim();
        phase.startDate = request.startDate();
        phase.endDate = request.endDate();
    }
}
