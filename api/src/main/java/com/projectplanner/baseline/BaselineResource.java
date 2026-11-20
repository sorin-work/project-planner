package com.projectplanner.baseline;

import com.projectplanner.baseline.BaselineDtos.BaselineRequest;
import com.projectplanner.baseline.BaselineDtos.BaselineResponse;
import com.projectplanner.baseline.BaselineDtos.ComparisonResponse;
import com.projectplanner.baseline.BaselineDtos.UpdateBaselineRequest;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import io.quarkus.security.Authenticated;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Baselines of a project. Everybody who can see the project can see its baselines
 * and how the plan compares with them, client viewers of its client included: they
 * see the dates already, and it is their project. Saving, renaming, choosing the
 * reference and deleting are for those who manage the project.
 */
@Path("/api")
@Authenticated
@Tag(name = "Baselines")
public class BaselineResource {

    private final OrgAccess access;
    private final BaselineService baselines;

    public BaselineResource(OrgAccess access, BaselineService baselines) {
        this.access = access;
        this.baselines = baselines;
    }

    @GET
    @Path("/projects/{projectId}/baselines")
    @Operation(summary = "List the baselines of a project, oldest first",
            description = "For everybody who can see the project, client viewers of its client included.")
    public List<BaselineResponse> list(@PathParam("projectId") long projectId) {
        return baselines.list(access.project(projectId));
    }

    @POST
    @Path("/projects/{projectId}/baselines")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Save the dates of a project's plan as a baseline",
            description = "For owners and managers. Copies the dates of every task and phase and the project's own dates as they are now. "
                    + "A baseline never changes afterwards. The first baseline of a project is its reference.")
    @Fails(status = 409, code = "baseline_limit", when = "the project already keeps ten baselines")
    @Fails(status = 409, code = "baseline_name_taken", when = "another baseline of the project has the name")
    public BaselineResponse take(@PathParam("projectId") long projectId, @Valid @NotNull BaselineRequest request) {
        Project project = access.project(projectId);
        Membership me = access.manageProject(project);
        return baselines.take(me, lock(project), request);
    }

    @PATCH
    @Path("/baselines/{baselineId}")
    @Transactional
    @Operation(summary = "Rename a baseline or make it the one the project is compared against",
            description = "For owners and managers. The dates of a baseline cannot be changed.")
    @Fails(status = 409, code = "baseline_name_taken", when = "another baseline of the project has the name")
    public BaselineResponse update(@PathParam("baselineId") long baselineId, @Valid @NotNull UpdateBaselineRequest request) {
        Baseline baseline = find(baselineId);
        Project project = access.project(baseline.projectId);
        access.manageProject(project);
        lock(project);
        reread(baseline);
        return baselines.update(baseline, request);
    }

    @DELETE
    @Path("/baselines/{baselineId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Delete a baseline",
            description = "For owners and managers. If it was the reference, the latest baseline that is left becomes it.")
    public void delete(@PathParam("baselineId") long baselineId) {
        Baseline baseline = find(baselineId);
        Project project = access.project(baseline.projectId);
        Membership me = access.manageProject(project);
        lock(project);
        reread(baseline);
        baselines.delete(me, project, baseline);
    }

    @GET
    @Path("/baselines/{baselineId}/comparison")
    @Operation(summary = "Compare the plan as it is now with a baseline",
            description = "For everybody who can see the project. Per task and per phase the dates then and now and how far the start "
                    + "and the finish moved in working days, the change of the project's end and of the plan's end, and the tasks added "
                    + "and removed since.")
    public ComparisonResponse comparison(@PathParam("baselineId") long baselineId) {
        Baseline baseline = find(baselineId);
        return baselines.compare(access.project(baseline.projectId), baseline);
    }

    /**
     * The project's row, locked until the request ends. Every change to the baselines of a
     * project takes it first, so two of them run one after the other and the second sees
     * the first: the limit of ten, a name that was just taken, the one reference. Nothing
     * else is locked here, so the order the rest of the API keeps (memberships, then
     * projects) holds.
     */
    private static Project lock(Project project) {
        return Project.locked(project.id);
    }

    /**
     * Reads the baseline again after the lock: what another request did in the meantime
     * counts, and a baseline that it deleted is gone for this one too.
     */
    private static void reread(Baseline baseline) {
        try {
            Baseline.getEntityManager().refresh(baseline);
        } catch (EntityNotFoundException e) {
            throw ApiException.notFound("Baseline");
        }
    }

    /** The baseline if the caller can see its project. Otherwise 404, whether it exists or not. */
    private Baseline find(long baselineId) {
        Baseline baseline = Baseline.findById(baselineId);
        if (baseline == null) {
            throw ApiException.notFound("Baseline");
        }
        try {
            access.project(baseline.projectId);
        } catch (ApiException e) {
            throw ApiException.notFound("Baseline");
        }
        return baseline;
    }
}
