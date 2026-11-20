package com.projectplanner.calendar;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class CalendarResourceTest {

    /** Monday in four weeks: far enough ahead that no clock problem can touch it. */
    private static final LocalDate MONDAY = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).plusWeeks(4);

    private static String org(Account owner) {
        return "/api/orgs/" + owner.orgId();
    }

    private static long holiday(Account who, LocalDate date, String name) {
        Number id = who.as().body(Map.of("date", date.toString(), "name", name)).post(org(who) + "/holidays")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    @Test
    void theTeamReadsHolidaysAndManagersWriteThem() {
        Account owner = Api.signup("Hilde");
        Account member = Api.signup("Ivo");
        Account viewer = Api.signup("Jade");
        Account stranger = Api.signup("Karl");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Acme"));

        long christmas = holiday(owner, MONDAY.plusDays(2), "Midweek holiday");
        holiday(owner, MONDAY, "Monday holiday");
        member.as().get(org(owner) + "/holidays").then().statusCode(200)
                .body("name", contains("Monday holiday", "Midweek holiday"));
        member.as().get(org(owner) + "/holidays?from=" + MONDAY.plusDays(1) + "&to=" + MONDAY.plusDays(6)).then().statusCode(200)
                .body("name", contains("Midweek holiday"));
        owner.as().get(org(owner) + "/holidays?from=" + MONDAY + "&to=" + MONDAY.minusDays(1)).then().statusCode(400)
                .body("code", equalTo("invalid_range"));

        member.as().body(Map.of("date", MONDAY.plusDays(3).toString(), "name", "Mine")).post(org(owner) + "/holidays").then().statusCode(403);
        viewer.as().get(org(owner) + "/holidays").then().statusCode(403);
        stranger.as().get(org(owner) + "/holidays").then().statusCode(404);
        owner.as().body(Map.of("date", MONDAY.toString(), "name", "Again")).post(org(owner) + "/holidays").then().statusCode(409)
                .body("code", equalTo("holiday_taken"));

        owner.as().body(Map.of("date", MONDAY.plusDays(3).toString(), "name", "Moved")).put(org(owner) + "/holidays/" + christmas)
                .then().statusCode(200).body("date", equalTo(MONDAY.plusDays(3).toString()));
        stranger.as().delete(org(stranger) + "/holidays/" + christmas).then().statusCode(404);
        owner.as().delete(org(owner) + "/holidays/" + christmas).then().statusCode(204);
        member.as().get(org(owner) + "/holidays").then().body("$", hasSize(1));
    }

    @Test
    void peopleManageTheirOwnTimeOffAndManagersAnybodys() {
        Account owner = Api.signup("Lars");
        Account member = Api.signup("Mona");
        Account other = Api.signup("Nils");
        Account viewer = Api.signup("Opal");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, other, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Acme"));
        Map<String, Object> week = Map.of("firstDay", MONDAY.toString(), "lastDay", MONDAY.plusDays(4).toString(), "note", "Away");

        Number own = member.as().body(week).post(org(owner) + "/time-off").then().statusCode(201)
                .body("userId", equalTo((int) member.userId())).extract().path("id");
        member.as().body(Map.of("userId", other.userId(), "firstDay", MONDAY.toString(), "lastDay", MONDAY.toString()))
                .post(org(owner) + "/time-off").then().statusCode(403);
        owner.as().body(Map.of("userId", other.userId(), "firstDay", MONDAY.toString(), "lastDay", MONDAY.toString()))
                .post(org(owner) + "/time-off").then().statusCode(201);
        owner.as().body(Map.of("userId", viewer.userId(), "firstDay", MONDAY.toString(), "lastDay", MONDAY.toString()))
                .post(org(owner) + "/time-off").then().statusCode(400).body("code", equalTo("unknown_member"));
        member.as().body(Map.of("firstDay", MONDAY.plusDays(4).toString(), "lastDay", MONDAY.plusDays(8).toString()))
                .post(org(owner) + "/time-off").then().statusCode(409).body("code", equalTo("time_off_overlaps"));
        member.as().body(Map.of("firstDay", MONDAY.toString(), "lastDay", MONDAY.minusDays(1).toString()))
                .post(org(owner) + "/time-off").then().statusCode(400).body("code", equalTo("invalid_dates"));
        viewer.as().body(week).post(org(owner) + "/time-off").then().statusCode(403);

        other.as().get(org(owner) + "/time-off").then().statusCode(200).body("$", hasSize(2));
        other.as().get(org(owner) + "/time-off?userId=" + member.userId()).then().statusCode(200).body("note", contains("Away"));
        other.as().delete(org(owner) + "/time-off/" + own).then().statusCode(403);
        // Moved by a day: it does not overlap with itself.
        member.as().body(Map.of("firstDay", MONDAY.plusDays(1).toString(), "lastDay", MONDAY.plusDays(4).toString()))
                .put(org(owner) + "/time-off/" + own).then().statusCode(200).body("firstDay", equalTo(MONDAY.plusDays(1).toString()));
        owner.as().delete(org(owner) + "/time-off/" + own).then().statusCode(204);
    }

    @Test
    void aHolidayIsSkippedByMovesAndTakenOffCapacityAndTimeOffOnlyLowersCapacity() {
        Account owner = Api.signup("Pia");
        long projectId = Api.createProject(owner, "HOL");
        long task = Api.createTask(owner, projectId, Map.of("title", "Two days", "estimateHours", 16, "assigneeId", owner.userId(),
                "startDate", MONDAY.toString(), "dueDate", MONDAY.plusDays(1).toString()));
        holiday(owner, MONDAY.plusDays(2), "Wednesday off");
        owner.as().body(Map.of("firstDay", MONDAY.plusWeeks(1).toString(), "lastDay", MONDAY.plusWeeks(1).plusDays(1).toString()))
                .post(org(owner) + "/time-off").then().statusCode(201);

        // A holiday changes no stored dates. Moving the task by a day keeps its two working days, past Wednesday.
        owner.as().get("/api/projects/" + projectId + "/tasks").then().body("[0].dueDate", equalTo(MONDAY.plusDays(1).toString()));
        owner.as().body(Map.of("days", 1)).post("/api/tasks/" + task + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo(MONDAY.plusDays(1).toString()))
                .body("task.dueDate", equalTo(MONDAY.plusDays(3).toString()));
        owner.as().get("/api/projects/" + projectId + "/critical-path").then().statusCode(200)
                .body("durationDays", equalTo(2))
                .body("finish", equalTo(MONDAY.plusDays(3).toString()));

        // The holiday takes a fifth off the first week, the two days off two fifths off the second.
        owner.as().get(org(owner) + "/workload?weeks=3&from=" + MONDAY).then().statusCode(200)
                .body("members[0].weeks[0].capacityHours", Api.num(32))
                .body("members[0].weeks[0].holidays", equalTo(1))
                .body("members[0].weeks[1].capacityHours", Api.num(24))
                .body("members[0].weeks[1].daysOff", equalTo(2))
                .body("members[0].weeks[2].capacityHours", Api.num(40));
    }
}
