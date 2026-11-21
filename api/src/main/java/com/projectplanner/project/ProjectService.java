package com.projectplanner.project;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.project.ProjectDtos.ProjectRequest;
import com.projectplanner.project.ProjectDtos.ProjectResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.projectplanner.common.Text.blankToNull;

@ApplicationScoped
public class ProjectService {

    /** The unique constraint on a project's code within its organisation, from the migration that creates the table. */
    private static final String CODE_CONSTRAINT = "uq_projects_org_code";

    private final ActivityLog activity;
    private final EntityManager em;
    private final Clock clock;
    private final Event<BudgetChanged> budgetChanged;
    private final Event<PlanChanged> planChanged;

    public ProjectService(ActivityLog activity, EntityManager em, Clock clock, Event<BudgetChanged> budgetChanged,
                          Event<PlanChanged> planChanged) {
        this.planChanged = planChanged;
        this.activity = activity;
        this.em = em;
        this.clock = clock;
        this.budgetChanged = budgetChanged;
    }

    public Project create(Membership me, ProjectRequest request) {
        Project project = insert(me.organisationId, request);
        activity.record(project.organisationId, project.id, null, me.userId, "project.created",
                "Created project " + project.name);
        return project;
    }

    /**
     * Checks and saves a new project and writes nothing to the activity log. For a
     * caller that has its own entry to write, like the copy of a project.
     */
    Project insert(long organisationId, ProjectRequest request) {
        Project project = new Project();
        project.organisationId = organisationId;
        project.createdAt = Instant.now(clock);
        apply(project, request);
        write(project, project::persist);
        return project;
    }

    public void update(Membership me, Project project, ProjectRequest request) {
        // Read again with the row locked, before anything changes: time that is being logged at
        // this moment waits, and the alert levels are the ones that are stored now.
        Project.getEntityManager().refresh(project, LockModeType.PESSIMISTIC_WRITE);
        ProjectStatus before = project.status;
        BigDecimal budgetHours = project.budgetHours;
        BigDecimal budgetAmount = project.budgetAmount;
        BigDecimal hourlyRate = project.hourlyRate;
        apply(project, request);
        write(project, () -> { });
        if (!same(budgetHours, project.budgetHours) || !same(budgetAmount, project.budgetAmount) || !same(hourlyRate, project.hourlyRate)) {
            budgetChanged.fire(new BudgetChanged(project.id));
        }
        // The end date and the status are part of the schedule alerts.
        planChanged.fire(new PlanChanged(project.id));
        if (before != project.status) {
            activity.record(project.organisationId, project.id, null, me.userId, "project.status",
                    "Changed status of " + project.name + " to " + project.status.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }

    private void apply(Project project, ProjectRequest request) {
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("invalid_dates", "The end date can't be before the start date");
        }
        String code = ProjectCodes.normalise(request.code());
        boolean taken = Project.<Project>find("organisationId = ?1 and code = ?2", project.organisationId, code)
                .stream().anyMatch(p -> !Objects.equals(p.id, project.id));
        if (taken) {
            throw codeTaken(code);
        }
        if (request.clientId() != null) {
            Client client = Client.findById(request.clientId());
            if (client == null || !Objects.equals(client.organisationId, project.organisationId)) {
                throw ApiException.badRequest("unknown_client", "Client not found in this organisation");
            }
        }
        project.name = request.name().trim();
        project.code = code;
        project.description = blankToNull(request.description());
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

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /**
     * Writes the project now and not at the end of the request. The check for a code
     * that is taken reads before it writes, so two requests that arrive together both
     * pass it, and only the unique constraint tells them apart. Its refusal is the same
     * answer as the check's, and here is where it can still be given.
     */
    private void write(Project project, Runnable persist) {
        try {
            persist.run();
            em.flush();
        } catch (PersistenceException e) {
            if (violates(e, CODE_CONSTRAINT)) {
                throw codeTaken(project.code);
            }
            throw e;
        }
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("code_taken", "Another project already uses the code " + code);
    }

    /** Whether a failed write was refused by the constraint with this name. */
    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String name = cause instanceof ConstraintViolationException violation ? violation.getConstraintName() : null;
            String text = (name == null ? "" : name) + " " + cause.getMessage();
            if (text.toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    public static ProjectResponse toResponse(Project p, boolean showMoney) {
        String clientName = null;
        if (p.clientId != null) {
            Client client = Client.findById(p.clientId);
            clientName = client == null ? null : client.name;
        }
        return toResponse(p, showMoney, clientName);
    }

    /** The same for a list of projects, with the names of their clients from one query. */
    public static List<ProjectResponse> toResponses(List<Project> projects, boolean showMoney) {
        Map<Long, String> clients = clientNames(projects);
        return projects.stream().map(p -> toResponse(p, showMoney, p.clientId == null ? null : clients.get(p.clientId))).toList();
    }

    /** The name of the client of each project that has one, by client id, from one query. */
    public static Map<Long, String> clientNames(List<Project> projects) {
        Set<Long> ids = projects.stream().map(p -> p.clientId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            Client.<Client>list("id in ?1", ids).forEach(c -> names.put(c.id, c.name));
        }
        return names;
    }

    private static ProjectResponse toResponse(Project p, boolean showMoney, String clientName) {
        return new ProjectResponse(p.id, p.organisationId, p.clientId, clientName, p.name, p.code, p.description,
                p.status, p.startDate, p.endDate, p.budgetHours,
                showMoney ? p.budgetAmount : null, showMoney ? p.hourlyRate : null,
                p.color, p.createdAt, p.updatedAt, null, null);
    }
}
