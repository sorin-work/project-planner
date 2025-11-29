package com.projectplanner.project;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.ProjectDtos.ProjectRequest;
import com.projectplanner.project.ProjectDtos.ProjectResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@ApplicationScoped
public class ProjectService {

    private final ActivityLog activity;
    private final Clock clock;

    public ProjectService(ActivityLog activity, Clock clock) {
        this.activity = activity;
        this.clock = clock;
    }

    public Project create(Membership me, ProjectRequest request) {
        Project project = new Project();
        project.organisationId = me.organisationId;
        project.createdAt = Instant.now(clock);
        apply(project, request);
        project.persist();
        activity.record(project.organisationId, project.id, null, me.userId, "project.created",
                "Created project " + project.name);
        return project;
    }

    public void update(Membership me, Project project, ProjectRequest request) {
        ProjectStatus before = project.status;
        apply(project, request);
        if (before != project.status) {
            activity.record(project.organisationId, project.id, null, me.userId, "project.status",
                    "Changed status of " + project.name + " to " + project.status.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }

    private void apply(Project project, ProjectRequest request) {
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("invalid_dates", "The end date can't be before the start date");
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        boolean taken = Project.<Project>find("organisationId = ?1 and code = ?2", project.organisationId, code)
                .count() > 0;
        if (taken) {
            throw ApiException.conflict("code_taken", "Another project already uses the code " + code);
        }
        if (request.clientId() != null) {
            Client client = Client.findById(request.clientId());
            if (client == null || !Objects.equals(client.organisationId, project.organisationId)) {
                throw ApiException.badRequest("unknown_client", "Client not found in this organisation");
            }
        }
        project.name = request.name().trim();
        project.code = code;
        project.description = request.description() == null || request.description().isBlank() ? null : request.description().trim();
        project.clientId = request.clientId();
        if (request.status() != null) {
            project.status = request.status();
        }
        project.startDate = request.startDate();
        project.endDate = request.endDate();
        project.budgetHours = request.budgetHours();
        project.budgetAmount = request.budgetAmount();
        project.hourlyRate = request.hourlyRate();
        if (request.color() != null) {
            project.color = request.color().toLowerCase(Locale.ROOT);
        }
        project.updatedAt = Instant.now(clock);
    }

    public static ProjectResponse toResponse(Project p, boolean showMoney) {
        String clientName = null;
        if (p.clientId != null) {
            Client client = Client.findById(p.clientId);
            clientName = client == null ? null : client.name;
        }
        return new ProjectResponse(p.id, p.organisationId, p.clientId, clientName, p.name, p.code, p.description,
                p.status, p.startDate, p.endDate, p.budgetHours,
                showMoney ? p.budgetAmount : null, showMoney ? p.hourlyRate : null,
                p.color, p.createdAt, p.updatedAt);
    }
}
