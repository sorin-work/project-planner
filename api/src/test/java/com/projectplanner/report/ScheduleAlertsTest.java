package com.projectplanner.report;

import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.TestClock;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ScheduleAlertsTest {

    /** New organisations are in UTC. */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Inject
    ScheduleAlerts alerts;

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    private record Team(Account owner, Account manager, Account member, long projectId) {
    }

    private static Team team(String code, LocalDate endDate) {
        Account owner = Api.signup("Odile");
        Account manager = Api.signup("Marek");
        Account member = Api.signup("Nils");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        Number id = owner.as().body(Map.of("name", "Launch " + code, "code", code, "status", "ACTIVE",
                        "startDate", TODAY.minusDays(30).toString(), "endDate", endDate.toString()))
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        return new Team(owner, manager, member, id.longValue());
    }

    private static long task(Account who, long projectId, LocalDate due) {
        return Api.createTask(who, projectId, Map.of("title", "Step", "startDate", due.minusDays(1).toString(), "dueDate", due.toString()));
    }

    private static List<String> alertsTo(Account who) {
        return QuarkusTransaction.requiringNew().call(() -> Notification.<Notification>list("userId = ?1 and type = ?2 order by id",
                who.userId(), NotificationType.SCHEDULE_ALERT).stream().map(n -> n.message).toList());
    }

    @Test
    void aPlanThatNowEndsAfterTheEndDateIsAnnouncedOnceToThoseWhoManage() {
        Team team = team("LATE", TODAY.plusDays(20));
        long last = task(team.member(), team.projectId(), TODAY.plusDays(10));
        assertThat(alertsTo(team.owner())).isEmpty();

        team.member().as().body(Map.of("startDate", TODAY.plusDays(24).toString(), "dueDate", TODAY.plusDays(25).toString()))
                .patch("/api/tasks/" + last).then().statusCode(200);
        String message = "Launch LATE now ends on " + TODAY.plusDays(25) + ", after its end date of " + TODAY.plusDays(20);
        assertThat(alertsTo(team.owner())).containsExactly(message);
        assertThat(alertsTo(team.manager())).containsExactly(message);
        assertThat(alertsTo(team.member())).isEmpty();
        team.owner().as().get("/api/notifications").then().statusCode(200)
                .body("[0].type", org.hamcrest.Matchers.equalTo("SCHEDULE_ALERT"))
                .body("[0].projectId", org.hamcrest.Matchers.equalTo((int) team.projectId()));

        // Later still: nothing new. Back in time and late again: announced again.
        team.member().as().body(Map.of("dueDate", TODAY.plusDays(28).toString())).patch("/api/tasks/" + last).then().statusCode(200);
        assertThat(alertsTo(team.owner())).hasSize(1);
        team.member().as().body(Map.of("startDate", TODAY.plusDays(5).toString(), "dueDate", TODAY.plusDays(6).toString()))
                .patch("/api/tasks/" + last).then().statusCode(200);
        assertThat(alertsTo(team.owner())).hasSize(1);
        team.member().as().body(Map.of("startDate", TODAY.plusDays(29).toString(), "dueDate", TODAY.plusDays(30).toString()))
                .patch("/api/tasks/" + last).then().statusCode(200);
        assertThat(alertsTo(team.owner())).hasSize(2);
    }

    @Test
    void offTrackIsAnnouncedOnceAndAgainOnlyAfterBeingBackOnTrack() {
        Team team = team("EDGE", TODAY.plusDays(60));
        // Five open tasks past their due date make a project off track.
        List<Long> overdue = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            overdue.add(task(team.owner(), team.projectId(), TODAY.minusDays(i)));
        }
        assertThat(alertsTo(team.manager())).containsExactly("Launch EDGE is off track");

        // On the edge: one done is at risk, a sixth is off track again, and nothing more is said.
        team.owner().as().body(Map.of("status", "DONE")).patch("/api/tasks/" + overdue.getFirst()).then().statusCode(200);
        task(team.owner(), team.projectId(), TODAY.minusDays(7));
        assertThat(alertsTo(team.manager())).hasSize(1);

        // Back on track, then off track again.
        for (Long id : openTaskIds(team)) {
            team.owner().as().body(Map.of("status", "DONE")).patch("/api/tasks/" + id).then().statusCode(200);
        }
        team.owner().as().get("/api/projects/" + team.projectId() + "/health").then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("ON_TRACK"));
        for (int i = 1; i <= 5; i++) {
            task(team.owner(), team.projectId(), TODAY.minusDays(i));
        }
        assertThat(alertsTo(team.manager())).hasSize(2);
    }

    private static List<Long> openTaskIds(Team team) {
        List<Integer> ids = team.owner().as().get("/api/projects/" + team.projectId() + "/tasks").then().statusCode(200)
                .extract().path("findAll { it.status != 'DONE' }.id");
        return ids.stream().map(Integer::longValue).toList();
    }

    @Test
    void theDailyPassFindsATaskThatWentOverdueOvernight() {
        Team team = team("NIGHT", TODAY.plusDays(60));
        for (int i = 0; i < 5; i++) {
            task(team.owner(), team.projectId(), TODAY.plusDays(1));
        }
        assertThat(alertsTo(team.owner())).isEmpty();

        // Three days on, nobody has touched anything, and the five tasks are overdue.
        TestClock.fix(Instant.now().plus(Duration.ofDays(3)));
        try {
            alerts.run();
            alerts.run();
        } finally {
            TestClock.reset();
        }
        assertThat(alertsTo(team.owner())).containsExactly("Launch NIGHT is off track");
    }
}
