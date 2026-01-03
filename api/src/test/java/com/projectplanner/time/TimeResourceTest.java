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
}
