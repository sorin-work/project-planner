package com.projectplanner.report;

import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import com.projectplanner.project.BudgetChanged;
import com.projectplanner.project.PlanChanged;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.report.ProjectHealthService.ProjectHealth;
import com.projectplanner.report.ScheduleAlertRule.Step;
import com.projectplanner.time.TimeChanged;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tells owners and managers when a project goes off track, and when its plan now ends after
 * its end date. See {@link ScheduleAlertRule} for when. Health changes when the plan changes,
 * which is looked at in the transaction of the change, after it, with the project's row
 * locked; and with nothing happening at all, when an open task passes its due date overnight,
 * which the daily pass at the start of each organisation's day looks at.
 */
@ApplicationScoped
public class ScheduleAlerts {

    private final ProjectHealthService health;
    private final Notifier notifier;
    private final Clock clock;

    public ScheduleAlerts(ProjectHealthService health, Notifier notifier, Clock clock) {
        this.health = health;
        this.notifier = notifier;
        this.clock = clock;
    }

    void planChanged(@Observes PlanChanged event) {
        check(event.projectId());
    }

    /** Logged time moves the burn and the progress, which the health is made of. */
    void timeChanged(@Observes TimeChanged event) {
        check(event.projectId());
    }

    void budgetChanged(@Observes BudgetChanged event) {
        check(event.projectId());
    }

    /**
     * Read again under the lock (the change itself took it first, see {@code TaskService}), so
     * that two changes at the same moment see each other's announced state. Only what the
     * alert needs is worked out: the status and the forecast end, not the schedule issues.
     */
    private void check(long projectId) {
        Project.getEntityManager().flush();
        Project project = Project.locked(projectId);
        if (project == null) {
            return;
        }
        announce(project, health.forAlerts(project));
    }

    @Scheduled(identity = "schedule-alerts", every = "60s", delayed = "40s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        run();
    }

    /**
     * The daily pass: once a day per organisation, on the first run of its day, every active
     * project is looked at in one go. Answers how many alerts went out.
     */
    @ActivateRequestContext
    public int run() {
        List<Organisation> organisations = QuarkusTransaction.requiringNew().call(() -> Organisation.<Organisation>listAll());
        int alerts = 0;
        for (Organisation org : organisations) {
            LocalDate today = org.today(clock);
            if (org.scheduleCheckedOn != null && !org.scheduleCheckedOn.isBefore(today)) {
                continue;
            }
            alerts += QuarkusTransaction.requiringNew().call(() -> pass(org.id, today));
        }
        return alerts;
    }

    /** One organisation's pass, if no other run took this day on already. */
    private int pass(long organisationId, LocalDate today) {
        int claimed = Organisation.update("scheduleCheckedOn = ?1 where id = ?2 and (scheduleCheckedOn is null or scheduleCheckedOn < ?1)",
                today, organisationId);
        if (claimed == 0) {
            return 0;
        }
        List<Project> projects = Project.<Project>find("organisationId = ?1 and status = ?2 order by id", organisationId,
                ProjectStatus.ACTIVE).withLock(LockModeType.PESSIMISTIC_WRITE).list();
        Map<Long, ProjectHealth> healths = health.healths(organisationId, projects, reader(organisationId), false);
        int alerts = 0;
        for (Project project : projects) {
            alerts += announce(project, healths.get(project.id));
        }
        return alerts;
    }

    private int announce(Project project, ProjectHealth h) {
        Step step = ScheduleAlertRule.step(project.offTrackAnnounced, project.lateEndAnnounced, h.status(), h.forecastEnd(),
                project.endDate);
        project.offTrackAnnounced = step.offTrackAnnounced();
        project.lateEndAnnounced = step.lateEndAnnounced();
        if (!step.announceOffTrack() && !step.announceLateEnd()) {
            return 0;
        }
        // The people who hear about a project's hours: the owners and managers, or with a lead the
        // lead and the owners. A member who leads hears of it too, since there are no amounts in it.
        // No actor: whoever moved the task hears of it too.
        List<Long> managers = BudgetAlerts.hearAboutHours(project, Membership.forOrganisation(project.organisationId));
        int alerts = 0;
        if (step.announceOffTrack()) {
            notifier.notifyAll(managers, project.organisationId, null, NotificationType.SCHEDULE_ALERT,
                    offTrackMessage(project), project.id, null);
            alerts++;
        }
        if (step.announceLateEnd()) {
            notifier.notifyAll(managers, project.organisationId, null, NotificationType.SCHEDULE_ALERT,
                    lateEndMessage(project, h.forecastEnd()), project.id, null);
            alerts++;
        }
        return alerts;
    }

    static String offTrackMessage(Project project) {
        return project.name + " is off track";
    }

    static String lateEndMessage(Project project, LocalDate forecastEnd) {
        return project.name + " now ends on " + forecastEnd + ", after its end date of " + project.endDate;
    }

    /** Health is read as an owner would: the status does not depend on who asks, only the money fields do. */
    private static Membership reader(long organisationId) {
        Membership owner = new Membership();
        owner.organisationId = organisationId;
        owner.role = Role.OWNER;
        return owner;
    }
}
