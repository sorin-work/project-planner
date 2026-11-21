package com.projectplanner.project;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ProjectLeadTest {

    /** Monday of last week: a week that is over, so every day of it can take time. */
    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).minusWeeks(1);

    private static Map<String, Object> body(String code, Long leadId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Project " + code, "code", code, "status", "ACTIVE",
                "budgetHours", 10, "budgetAmount", 1000, "hourlyRate", 100));
        if (leadId != null) {
            body.put("leadId", leadId);
        }
        return body;
    }

    private static long project(Account owner, String code, Long leadId) {
        Number id = owner.as().body(body(code, leadId)).post("/api/orgs/" + owner.orgId() + "/projects")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static void log(Account who, Account owner, long projectId, int minutes) {
        who.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", minutes, "billable", true))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
    }

    private static List<String> alerts(Account who) {
        return who.as().get("/api/notifications?limit=200").then().statusCode(200)
                .extract().jsonPath().getList("findAll { it.type == 'BUDGET_ALERT' }.message");
    }

    private static long memberId(Account owner, Account who) {
        Number id = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().statusCode(200)
                .extract().path("find { it.userId == " + who.userId() + " }.id");
        return id.longValue();
    }

    /** An owner, two managers, a member and a client viewer in one organisation. */
    private record Team(Account owner, Account lead, Account otherManager, Account member, Account viewer) {
    }

    private static Team team(String prefix) {
        Account owner = Api.signup(prefix + " Owner");
        Account lead = Api.signup(prefix + " Lead");
        Account otherManager = Api.signup(prefix + " Manager");
        Account member = Api.signup(prefix + " Member");
        Account viewer = Api.signup(prefix + " Viewer");
        Api.addMember(owner, lead, "MANAGER");
        Api.addMember(owner, otherManager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, prefix + " Client"));
        return new Team(owner, lead, otherManager, member, viewer);
    }

    @Test
    void anOwnerAManagerOrAMemberLeadsAndTheProjectSaysWho() {
        Team t = team("Ada");
        long projectId = project(t.owner(), "LEAD", t.member().userId());
        t.owner().as().get("/api/projects/" + projectId).then().statusCode(200)
                .body("leadId", equalTo((int) t.member().userId())).body("leadName", equalTo("Ada Member"));
        t.owner().as().get("/api/orgs/" + t.owner().orgId() + "/projects").then().statusCode(200)
                .body("find { it.project.id == " + projectId + " }.project.leadName", equalTo("Ada Member"));

        // A change of project that leaves leadId out leaves the project without a lead, like any other field.
        t.owner().as().body(body("LEAD", null)).put("/api/projects/" + projectId).then().statusCode(200)
                .body("leadId", nullValue()).body("leadName", nullValue());
        t.owner().as().body(body("LEAD", t.lead().userId())).put("/api/projects/" + projectId).then().statusCode(200)
                .body("leadName", equalTo("Ada Lead"));
    }

    @Test
    void aClientViewerOrSomebodyFromElsewhereCannotLead() {
        Team t = team("Bo");
        Account stranger = Api.signup("Bo Stranger");
        t.owner().as().body(body("NOPE", t.viewer().userId())).post("/api/orgs/" + t.owner().orgId() + "/projects")
                .then().statusCode(400).body("code", equalTo("client_cannot_lead"));
        t.owner().as().body(body("NOPE", stranger.userId())).post("/api/orgs/" + t.owner().orgId() + "/projects")
                .then().statusCode(400).body("code", equalTo("unknown_lead"));
        long projectId = project(t.owner(), "NOPE", null);
        t.owner().as().body(body("NOPE", stranger.userId())).put("/api/projects/" + projectId)
                .then().statusCode(400).body("code", equalTo("unknown_lead"));
        // Only owners and managers set it, as the rest of the project.
        t.member().as().body(body("NOPE", t.member().userId())).put("/api/projects/" + projectId).then().statusCode(403);
    }

    @Test
    void withALeadTheLeadAndTheOwnersHearAndAMemberLeadNeverHearsAboutMoney() {
        Team t = team("Cy");
        long managed = project(t.owner(), "MGD", t.lead().userId());
        long membered = project(t.owner(), "MBR", t.member().userId());

        // Eight of ten hours and 800 of 1000: both thresholds at once.
        log(t.member(), t.owner(), managed, 480);
        log(t.member(), t.owner(), membered, 480);

        assertThat(alerts(t.owner())).hasSize(4);
        assertThat(alerts(t.lead())).hasSize(2).allMatch(m -> m.startsWith("Project MGD"));
        assertThat(alerts(t.otherManager())).as("not their project").isEmpty();
        assertThat(alerts(t.member())).containsExactly("Project MBR has used 80% of its hours budget: 8 of 10 hours");
        assertThat(alerts(t.viewer())).isEmpty();
    }

    @Test
    void aLeadWhoLeavesIsClearedAndTheAlertsGoBackToTheManagers() {
        Team t = team("Di");
        long projectId = project(t.owner(), "LEFT", t.lead().userId());
        long other = project(t.owner(), "STAY", t.member().userId());

        t.owner().as().delete("/api/orgs/" + t.owner().orgId() + "/members/" + memberId(t.owner(), t.lead())).then().statusCode(204);
        t.owner().as().get("/api/projects/" + projectId).then().statusCode(200).body("leadId", nullValue());
        t.owner().as().get("/api/projects/" + other).then().statusCode(200).body("leadId", equalTo((int) t.member().userId()));

        log(t.member(), t.owner(), projectId, 480);
        assertThat(alerts(t.otherManager())).hasSize(2);
        assertThat(alerts(t.owner())).hasSize(2);

        // Made a client viewer: no longer a lead either.
        t.owner().as().body(Map.of("role", "CLIENT", "clientId", Api.createClient(t.owner(), "Di Other")))
                .patch("/api/orgs/" + t.owner().orgId() + "/members/" + memberId(t.owner(), t.member())).then().statusCode(200);
        t.owner().as().get("/api/projects/" + other).then().statusCode(200).body("leadId", nullValue());
    }

    @Test
    void aCopyTakesTheLeadOnlyWithTheAssignees() {
        Team t = team("Eve");
        long source = project(t.owner(), "SRC", t.member().userId());
        String start = WEEK.plusWeeks(4).toString();
        t.owner().as().body(Map.of("name", "Copy one", "code", "CPY-1", "startDate", start, "keepAssignees", false))
                .post("/api/projects/" + source + "/duplicate").then().statusCode(201).body("leadId", nullValue());
        t.owner().as().body(Map.of("name", "Copy two", "code", "CPY-2", "startDate", start, "keepAssignees", true))
                .post("/api/projects/" + source + "/duplicate").then().statusCode(201)
                .body("leadId", equalTo((int) t.member().userId())).body("leadName", equalTo("Eve Member"));
    }

    @Test
    void theListSaysWhoLeadsEachProject() {
        Team t = team("Fia");
        project(t.owner(), "FIA-A", t.lead().userId());
        project(t.owner(), "FIA-B", null);
        t.member().as().get("/api/orgs/" + t.owner().orgId() + "/projects").then().statusCode(200)
                .body("project.code", contains("FIA-A", "FIA-B"))
                .body("project.leadName", contains("Fia Lead", null));
    }
}
