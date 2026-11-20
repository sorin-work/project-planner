package com.projectplanner.baseline;

import com.projectplanner.baseline.BaselineDtos.BaselineRequest;
import com.projectplanner.baseline.BaselineDtos.BaselineResponse;
import com.projectplanner.baseline.BaselineDtos.ComparisonResponse;
import com.projectplanner.baseline.BaselineDtos.UpdateBaselineRequest;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
public class BaselineResource {

    private final OrgAccess access;
    private final BaselineService baselines;

    public BaselineResource(OrgAccess access, BaselineService baselines) {
        this.access = access;
        this.baselines = baselines;
    }

    @GET
    @Path("/projects/{projectId}/baselines")
    public List<BaselineResponse> list(@PathParam("projectId") long projectId) {
        return baselines.list(access.project(projectId));
    }

    @POST
    @Path("/projects/{projectId}/baselines")
    @Transactional
    @ResponseStatus(201)
    public BaselineResponse take(@PathParam("projectId") long projectId, @Valid @NotNull BaselineRequest request) {
        Project project = access.project(projectId);
        Membership me = access.manageProject(project);
        return baselines.take(me, project, request);
    }

    @PATCH
    @Path("/baselines/{baselineId}")
    @Transactional
    public BaselineResponse update(@PathParam("baselineId") long baselineId, @Valid @NotNull UpdateBaselineRequest request) {
        Baseline baseline = find(baselineId);
        access.manageProject(access.project(baseline.projectId));
        return baselines.update(baseline, request);
    }

    @DELETE
    @Path("/baselines/{baselineId}")
    @Transactional
    @ResponseStatus(204)
    public void delete(@PathParam("baselineId") long baselineId) {
        Baseline baseline = find(baselineId);
        Project project = access.project(baseline.projectId);
        Membership me = access.manageProject(project);
        baselines.delete(me, project, baseline);
    }

    @GET
    @Path("/baselines/{baselineId}/comparison")
    public ComparisonResponse comparison(@PathParam("baselineId") long baselineId) {
        Baseline baseline = find(baselineId);
        return baselines.compare(access.project(baseline.projectId), baseline);
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
