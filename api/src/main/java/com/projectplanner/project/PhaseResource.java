package com.projectplanner.project;

import com.projectplanner.common.ApiException;
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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

@Path("/api")
@Authenticated
public class PhaseResource {

    private final OrgAccess access;

    public PhaseResource(OrgAccess access) {
        this.access = access;
    }

    @GET
    @Path("/projects/{projectId}/phases")
    public List<PhaseResponse> list(@PathParam("projectId") long projectId) {
        access.project(projectId);
        return Phase.forProject(projectId).stream().map(PhaseResponse::of).toList();
    }

    @POST
    @Path("/projects/{projectId}/phases")
    @Transactional
    @ResponseStatus(201)
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
        phase.name = request.name().trim();
        phase.startDate = request.startDate();
        phase.endDate = request.endDate();
    }
}
