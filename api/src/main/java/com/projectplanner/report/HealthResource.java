package com.projectplanner.report;

import com.projectplanner.org.OrgAccess;
import com.projectplanner.project.Project;
import com.projectplanner.report.ProjectHealthService.ProjectHealth;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/api/projects/{projectId}/health")
@Authenticated
public class HealthResource {

    private final OrgAccess access;
    private final ProjectHealthService health;

    public HealthResource(OrgAccess access, ProjectHealthService health) {
        this.access = access;
        this.health = health;
    }

    @GET
    public ProjectHealth get(@PathParam("projectId") long projectId) {
        Project project = access.project(projectId);
        return health.health(project, access.member(project.organisationId));
    }
}
