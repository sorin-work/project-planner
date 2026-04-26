package com.projectplanner.time;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class TimeResourceTest {

    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now().minusWeeks(1));

    private static Map<String, Object> entry(long projectId, LocalDate date, int minutes) {
        return Map.of("projectId", projectId, "date", date.toString(), "minutes", minutes, "description", "Work");
    }

    @Test
    void logsTimeAndShowsItInTheWeek() {
        Account owner = Api.signup("Ada");
        long projectId = Api.createProject(owner, "TIME");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Design"));
        owner.as().body(Map.of("projectId", projectId, "taskId", taskId, "date", WEEK.toString(), "minutes", 90, "billable", true))
                .post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(201).body("taskTitle", equalTo("Design")).body("locked", equalTo(false));
        owner.as().body(entry(projectId, WEEK.plusDays(2), 30))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        owner.as().queryParam("date", WEEK.plusDays(3).toString())
                .get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                .then().statusCode(200)
                .body("weekStart", equalTo(WEEK.toString()))
                .body("status", equalTo("DRAFT"))
                .body("totalMinutes", equalTo(120))
                .body("days[0].minutes", equalTo(90))
                .body("days[2].minutes", equalTo(30))
                .body("entries", hasSize(2));
        owner.as().get("/api/tasks/" + taskId).then().body("loggedHours", Api.num(1.5));
    }

    @Test
    void validatesEntries() {
        Account owner = Api.signup("Bo");
        long projectId = Api.createProject(owner, "VAL");
        owner.as().body(entry(projectId, WEEK, 0)).post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(400).body("errors.field", hasItem("minutes"));
        Account stranger = Api.signup("Cy");
        long otherProject = Api.createProject(stranger, "OTHER");
        owner.as().body(entry(otherProject, WEEK, 60)).post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(400).body("code", equalTo("unknown_project"));
    }

    @Test
    void timerStartsAndStops() {
        Account owner = Api.signup("Di");
        long projectId = Api.createProject(owner, "TIMER");
        owner.as().get("/api/orgs/" + owner.orgId() + "/timer").then().statusCode(204);
        owner.as().body(Map.of("projectId", projectId, "description", "Pairing"))
                .post("/api/orgs/" + owner.orgId() + "/timer/start")
                .then().statusCode(201).body("running", equalTo(true));
        owner.as().get("/api/orgs/" + owner.orgId() + "/timer").then().statusCode(200).body("description", equalTo("Pairing"));
        owner.as().post("/api/orgs/" + owner.orgId() + "/timer/stop")
                .then().statusCode(200).body("running", equalTo(false)).body("minutes", equalTo(1));
        owner.as().post("/api/orgs/" + owner.orgId() + "/timer/stop").then().statusCode(409).body("code", equalTo("no_timer"));
    }

    @Test
    void submittedWeeksAreLockedUntilRejected() {
        Account owner = Api.signup("Eli");
        Account member = Api.signup("Fay");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "SHEET");
        Number entryId = member.as().body(entry(projectId, WEEK, 120))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201).extract().path("id");

        Number sheetId = member.as().body(Map.of("weekStart", WEEK.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit")
                .then().statusCode(200).body("status", equalTo("SUBMITTED")).extract().path("timesheetId");

        member.as().body(entry(projectId, WEEK.plusDays(1), 60))
                .post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(409).body("code", equalTo("timesheet_locked"));
        member.as().delete("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId).then().statusCode(409);

        owner.as().get("/api/orgs/" + owner.orgId() + "/timesheets")
                .then().statusCode(200).body("id", hasItem(sheetId.intValue()));
        owner.as().get("/api/notifications").then().body("type", hasItem("TIMESHEET_SUBMITTED"));

        owner.as().body(Map.of("note", "Wrong project on Monday"))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/reject")
                .then().statusCode(200).body("status", equalTo("REJECTED")).body("reviewNote", equalTo("Wrong project on Monday"));
        member.as().get("/api/notifications").then().body("[0].type", equalTo("TIMESHEET_REJECTED"));
        member.as().delete("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId).then().statusCode(204);
    }

    @Test
    void managersApproveButNotTheirOwnWeek() {
        Account owner = Api.signup("Gus");
        Account manager = Api.signup("Hal");
        Api.addMember(owner, manager, "MANAGER");
        long projectId = Api.createProject(owner, "APPROVE");
        manager.as().body(entry(projectId, WEEK, 60)).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        Number sheetId = manager.as().body(Map.of("weekStart", WEEK.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200).extract().path("timesheetId");

        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/approve")
                .then().statusCode(200).body("status", equalTo("APPROVED")).body("reviewedByName", equalTo("Gus"));
        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/approve").then().statusCode(409);
    }

    @Test
    void membersOnlySeeTheirOwnTime() {
        Account owner = Api.signup("Jo");
        Account member = Api.signup("Kim");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "PRIV");
        owner.as().body(entry(projectId, WEEK, 60)).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        member.as().body(entry(projectId, WEEK, 30)).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        String range = "?from=" + WEEK + "&to=" + WEEK.plusDays(6);
        member.as().get("/api/orgs/" + owner.orgId() + "/time-entries" + range).then().statusCode(200).body("$", hasSize(1));
        owner.as().get("/api/orgs/" + owner.orgId() + "/time-entries" + range).then().statusCode(200).body("$", hasSize(2));
        member.as().get("/api/orgs/" + owner.orgId() + "/time-entries" + range + "&userId=" + owner.userId()).then().statusCode(403);
    }

    @Test
    void exportsCsv() {
        Account owner = Api.signup("Lou");
        Account member = Api.signup("Mo");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "CSV");
        owner.as().body(Map.of("rates", java.util.List.of(Map.of("role", "MEMBER", "hourlyRate", 80))))
                .put("/api/orgs/" + owner.orgId() + "/role-rates").then().statusCode(200);
        member.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 90,
                        "description", "=cmd|' /C calc'!A0, then a comma", "billable", true))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        String range = "?from=" + WEEK + "&to=" + WEEK.plusDays(6);
        String csv = owner.as().get("/api/orgs/" + owner.orgId() + "/exports/timesheets.csv" + range)
                .then().statusCode(200)
                .header("Content-Disposition", containsString("timesheets-" + WEEK))
                .contentType(startsWith("text/csv"))
                .extract().asString();
        String[] lines = csv.split("\r\n");
        org.assertj.core.api.Assertions.assertThat(lines).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(lines[0]).startsWith("date,week,member").endsWith("rate,amount");
        org.assertj.core.api.Assertions.assertThat(lines[1]).contains("\"'=cmd|' /C calc'!A0, then a comma\"").endsWith(",1.50,yes,draft,80.00,120.00");

        String own = member.as().get("/api/orgs/" + owner.orgId() + "/exports/timesheets.csv" + range)
                .then().statusCode(200).extract().asString();
        org.assertj.core.api.Assertions.assertThat(own.split("\r\n")[0]).doesNotContain("rate");
        member.as().get("/api/orgs/" + owner.orgId() + "/exports/timesheets.csv" + range + "&userId=" + owner.userId())
                .then().statusCode(403);
    }

    @Test
    void finishedProjectsTakeNoMoreTime() {
        Account owner = Api.signup("Ned");
        long projectId = Api.createProject(owner, "FIN");
        owner.as().body(Map.of("name", "Finished", "code", "FIN", "status", "DONE"))
                .put("/api/projects/" + projectId).then().statusCode(200);
        owner.as().body(entry(projectId, WEEK, 60)).post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(400).body("code", equalTo("project_done"));
        owner.as().get("/api/orgs/" + owner.orgId() + "/projects").then().body("project.status", not(hasItem("ACTIVE")));
    }
}
