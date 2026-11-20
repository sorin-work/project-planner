package com.projectplanner.time;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.TestClock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
        owner.as().body(entry(projectId, LocalDate.now().plusDays(30), 60)).post("/api/orgs/" + owner.orgId() + "/time-entries")
                .then().statusCode(400).body("code", equalTo("date_in_future"));
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
        // Changing an entry is refused as well, whether it is in the locked week or would move into it.
        member.as().body(entry(projectId, WEEK, 30))
                .put("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId)
                .then().statusCode(409).body("code", equalTo("timesheet_locked"));
        Number later = member.as().body(entry(projectId, WEEK.plusDays(7), 45))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201).extract().path("id");
        member.as().body(entry(projectId, WEEK.plusDays(2), 45))
                .put("/api/orgs/" + owner.orgId() + "/time-entries/" + later)
                .then().statusCode(409).body("code", equalTo("timesheet_locked"));
        member.as().queryParam("date", WEEK.toString()).get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                .then().body("totalMinutes", equalTo(120)).body("entries[0].locked", equalTo(true));

        owner.as().get("/api/orgs/" + owner.orgId() + "/timesheets")
                .then().statusCode(200).body("id", hasItem(sheetId.intValue()));
        owner.as().get("/api/notifications").then().body("type", hasItem("TIMESHEET_SUBMITTED"))
                .body("weekStart", hasItem(WEEK.toString()));

        owner.as().body(Map.of("note", "Wrong project on Monday"))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/reject")
                .then().statusCode(200).body("status", equalTo("REJECTED")).body("reviewNote", equalTo("Wrong project on Monday"));
        member.as().get("/api/notifications").then().body("[0].type", equalTo("TIMESHEET_REJECTED"))
                .body("[0].weekStart", equalTo(WEEK.toString()));
        member.as().delete("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId).then().statusCode(204);
    }

    @Test
    void changingAnEntryReplacesItAsAWhole() {
        Account owner = Api.signup("Odo");
        Account other = Api.signup("Pim");
        Api.addMember(owner, other, "MEMBER");
        long projectId = Api.createProject(owner, "EDIT");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Build"));
        String entries = "/api/orgs/" + owner.orgId() + "/time-entries";
        // An internal project: time on it is not billable unless the entry says so.
        Number entryId = owner.as().body(Map.of("projectId", projectId, "taskId", taskId, "date", WEEK.toString(), "minutes", 60,
                        "description", "Pairing"))
                .post(entries).then().statusCode(201).body("billable", equalTo(false)).extract().path("id");

        // What the weekly grid sends for a new time: everything else as it was.
        owner.as().body(Map.of("projectId", projectId, "taskId", taskId, "date", WEEK.toString(), "minutes", 95,
                        "description", "Pairing", "billable", false))
                .put(entries + "/" + entryId).then().statusCode(200)
                .body("id", equalTo(entryId)).body("minutes", equalTo(95))
                .body("description", equalTo("Pairing")).body("billable", equalTo(false)).body("taskTitle", equalTo("Build"));
        // A note that is not sent back is gone, and a missing flag stays as it was.
        owner.as().body(Map.of("projectId", projectId, "taskId", taskId, "date", WEEK.toString(), "minutes", 30))
                .put(entries + "/" + entryId).then().statusCode(200)
                .body("minutes", equalTo(30)).body("description", nullValue()).body("billable", equalTo(false));
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 0))
                .put(entries + "/" + entryId).then().statusCode(400).body("errors.field", hasItem("minutes"));
        other.as().body(entry(projectId, WEEK, 15)).put(entries + "/" + entryId).then().statusCode(403);

        owner.as().queryParam("date", WEEK.toString()).get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                .then().body("entries", hasSize(1)).body("totalMinutes", equalTo(30));
    }

    @Test
    void aCreateThatIsSentAgainMakesOneEntry() {
        Account owner = Api.signup("Rex");
        Account member = Api.signup("Sam");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "TWICE");
        String entries = "/api/orgs/" + owner.orgId() + "/time-entries";
        Map<String, Object> create = Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 90,
                "clientKey", "0f2c4e6a8b1d3f57");

        Number first = owner.as().body(create).post(entries).then().statusCode(201).body("minutes", equalTo(90)).extract().path("id");
        // The answer got lost and the same create is sent again, the second time with another time typed.
        owner.as().body(create).post(entries).then().statusCode(201).body("id", equalTo(first));
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 120, "clientKey", "0f2c4e6a8b1d3f57"))
                .post(entries).then().statusCode(201).body("id", equalTo(first)).body("minutes", equalTo(90));
        owner.as().queryParam("date", WEEK.toString()).get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                .then().body("entries", hasSize(1)).body("totalMinutes", equalTo(90));

        // Another key is another entry, and so is the same key from somebody else or without a key at all.
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 30, "clientKey", "another-key-1"))
                .post(entries).then().statusCode(201).body("id", not(equalTo(first)));
        member.as().body(create).post(entries).then().statusCode(201).body("id", not(equalTo(first))).body("userId", equalTo((int) member.userId()));
        owner.as().body(entry(projectId, WEEK, 15)).post(entries).then().statusCode(201);
        owner.as().body(entry(projectId, WEEK, 15)).post(entries).then().statusCode(201);
        owner.as().queryParam("date", WEEK.toString()).get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                .then().body("entries", hasSize(4)).body("totalMinutes", equalTo(150));

        // A key stands for one cell: it is not taken for another day, and it has a sensible length.
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.plusDays(1).toString(), "minutes", 90, "clientKey", "0f2c4e6a8b1d3f57"))
                .post(entries).then().statusCode(409).body("code", equalTo("client_key_reused"));
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 90, "clientKey", "short"))
                .post(entries).then().statusCode(400).body("errors.field", hasItem("clientKey"));

        // A repeat still gets its entry back once the week is submitted. It changes nothing.
        owner.as().body(Map.of("weekStart", WEEK.toString())).post("/api/orgs/" + owner.orgId() + "/timesheets/submit").then().statusCode(200);
        owner.as().body(create).post(entries).then().statusCode(201).body("id", equalTo(first)).body("locked", equalTo(true));
        owner.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 90, "clientKey", "a-brand-new-key"))
                .post(entries).then().statusCode(409).body("code", equalTo("timesheet_locked"));
    }

    @Test
    void thisWeekIsTheOrganisationsWeek() {
        Account owner = Api.signup("Quy");
        owner.as().body(Map.of("timezone", "Pacific/Kiritimati")).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        try {
            // 2026-03-08 is a Sunday. At eight in the evening UTC it is already Monday on the island.
            TestClock.fix(Instant.parse("2026-03-08T20:00:00Z"));
            owner.as().get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                    .then().statusCode(200).body("weekStart", equalTo("2026-03-09")).body("days[0].date", equalTo("2026-03-09"));
            owner.as().queryParam("date", "2026-03-08").get("/api/orgs/" + owner.orgId() + "/timesheets/week")
                    .then().statusCode(200).body("weekStart", equalTo("2026-03-02"));
        } finally {
            TestClock.reset();
        }
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

        manager.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/approve").then().statusCode(403);
        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/approve")
                .then().statusCode(200).body("status", equalTo("APPROVED")).body("reviewedByName", equalTo("Gus"));
        owner.as().post("/api/orgs/" + owner.orgId() + "/timesheets/" + sheetId + "/approve").then().statusCode(409);
    }

    @Test
    void emptyWeeksCannotBeSubmitted() {
        Account owner = Api.signup("Ian");
        owner.as().body(Map.of("weekStart", WEEK.toString()))
                .post("/api/orgs/" + owner.orgId() + "/timesheets/submit")
                .then().statusCode(400).body("code", equalTo("empty_timesheet"));
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
