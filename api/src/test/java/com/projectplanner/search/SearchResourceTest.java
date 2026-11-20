package com.projectplanner.search;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SearchResourceTest {

    private static ValidatableResponse search(Account who, long orgId, String q) {
        return who.as().queryParam("q", q).get("/api/orgs/" + orgId + "/search").then();
    }

    private static ValidatableResponse search(Account who, String q) {
        return search(who, who.orgId(), q).statusCode(200);
    }

    private static long comment(Account who, long taskId, String body) {
        Number id = who.as().body(Map.of("body", body)).post("/api/tasks/" + taskId + "/comments").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    @Test
    void findsProjectsTasksAndCommentsEachInItsGroup() {
        Account owner = Api.signup("Uma");
        long clientId = Api.createClient(owner, "Zeppelin Works");
        long website = Api.createProject(owner, "ZW-WEB", clientId);
        long other = Api.createProject(owner, "INTRANET");
        long byTitle = Api.createTask(owner, website, Map.of("title", "Zeppelin landing page"));
        long byDescription = Api.createTask(owner, other, Map.of("title", "Order prints",
                "description", "Ask the printer for a quote.\nThe zeppelin poster comes first."));
        long commentId = comment(owner, byDescription, "Do we have the Zeppelin logo as a vector?");

        search(owner, "zeppelin")
                .body("query", equalTo("zeppelin"))
                // By the name of its client. Nothing about money, whoever asks.
                .body("projects", hasSize(1))
                .body("projects[0]", equalTo(Map.of("type", "PROJECT", "id", (int) website, "projectId", (int) website,
                        "projectCode", "ZW-WEB", "title", "Project ZW-WEB", "snippet", "Zeppelin Works")))
                // The title before the description, and the description with the text around the match.
                .body("tasks.id", contains((int) byTitle, (int) byDescription))
                .body("tasks[0]", equalTo(Map.of("type", "TASK", "id", (int) byTitle, "projectId", (int) website,
                        "projectCode", "ZW-WEB", "taskId", (int) byTitle, "title", "Zeppelin landing page")))
                .body("tasks[1].snippet", equalTo("…the printer for a quote. The zeppelin poster comes first."))
                .body("tasks[1].projectCode", equalTo("INTRANET"))
                // A comment leads to its task.
                .body("comments", hasSize(1))
                .body("comments[0]", equalTo(Map.of("type", "COMMENT", "id", (int) commentId, "projectId", (int) other,
                        "projectCode", "INTRANET", "taskId", (int) byDescription, "title", "Order prints",
                        "snippet", "Do we have the Zeppelin logo as a vector?")));

        search(owner, "zw-w").body("projects.projectCode", contains("ZW-WEB")).body("tasks", empty());
        search(owner, "  ZEPPELIN  ").body("query", equalTo("ZEPPELIN")).body("tasks", hasSize(2));
    }

    @Test
    void aPercentSignOrAnUnderscoreMatchesItself() {
        Account owner = Api.signup("Vic");
        long projectId = Api.createProject(owner, "WILD");
        long percent = Api.createTask(owner, projectId, Map.of("title", "Reach 100% coverage"));
        Api.createTask(owner, projectId, Map.of("title", "Reach 1000 users"));
        long underscore = Api.createTask(owner, projectId, Map.of("title", "Rename user_id"));
        Api.createTask(owner, projectId, Map.of("title", "Rename userXid"));
        long bang = Api.createTask(owner, projectId, Map.of("title", "Ship it!"));

        search(owner, "100%").body("tasks.id", contains((int) percent));
        search(owner, "user_id").body("tasks.id", contains((int) underscore));
        search(owner, "it!").body("tasks.id", contains((int) bang));
        search(owner, "%%").body("tasks", empty());
        search(owner, "__").body("tasks", empty());
    }

    @Test
    void tooShortFindsNothingAndTooLongIsRefused() {
        Account owner = Api.signup("Wes");
        long projectId = Api.createProject(owner, "SHORT");
        Api.createTask(owner, projectId, Map.of("title", "Ab"));

        search(owner, "a").body("projects", empty()).body("tasks", empty()).body("comments", empty());
        search(owner, " a ").body("tasks", empty());
        owner.as().get("/api/orgs/" + owner.orgId() + "/search").then().statusCode(200).body("query", equalTo("")).body("tasks", empty());
        search(owner, "ab").body("tasks", hasSize(1));
        search(owner, owner.orgId(), "x".repeat(101)).statusCode(400).body("code", equalTo("validation_failed"))
                .body("errors[0].field", equalTo("q"));
    }

    @Test
    void theStartOfATitleComesFirstAndOpenTasksBeforeDoneOnes() {
        Account owner = Api.signup("Xan");
        long projectId = Api.createProject(owner, "ORDER");
        long inDescription = Api.createTask(owner, projectId, Map.of("title", "Kick-off", "description", "Bring the brief"));
        long contains = Api.createTask(owner, projectId, Map.of("title", "Write the brief"));
        long doneStarts = Api.createTask(owner, projectId, Map.of("title", "Brief the agency", "status", "DONE"));
        long starts = Api.createTask(owner, projectId, Map.of("title", "Briefing for the board"));

        search(owner, "brief").body("tasks.id", contains((int) starts, (int) doneStarts, (int) contains, (int) inDescription));
    }

    @Test
    void theNumberOfHitsOfAKindIsBounded() {
        Account owner = Api.signup("Yara");
        long projectId = Api.createProject(owner, "MANY");
        for (int i = 0; i < 22; i++) {
            Api.createTask(owner, projectId, Map.of("title", "Chapter " + i));
        }

        search(owner, "chapter").body("tasks", hasSize(8));
        owner.as().queryParam("q", "chapter").queryParam("limit", 3).get("/api/orgs/" + owner.orgId() + "/search")
                .then().body("tasks", hasSize(3));
        owner.as().queryParam("q", "chapter").queryParam("limit", 1000).get("/api/orgs/" + owner.orgId() + "/search")
                .then().body("tasks", hasSize(20));
        owner.as().queryParam("q", "chapter").queryParam("limit", 0).get("/api/orgs/" + owner.orgId() + "/search")
                .then().body("tasks", hasSize(1));
    }

    @Test
    void nothingIsFoundInAnOrganisationYouAreNotIn() {
        Account owner = Api.signup("Zed");
        long projectId = Api.createProject(owner, "SECRET");
        long taskId = Api.createTask(owner, projectId, Map.of("title", "Quarterly numbers"));
        comment(owner, taskId, "The quarterly numbers are in");
        Account stranger = Api.signup("Abe");
        long strangersProject = Api.createProject(stranger, "OWN");
        Api.createTask(stranger, strangersProject, Map.of("title", "My own quarterly review"));

        // Not their organisation: the same answer as for one that does not exist.
        search(stranger, owner.orgId(), "quarterly").statusCode(404).body("code", equalTo("not_found"));
        search(stranger, 987654321L, "quarterly").statusCode(404).body("code", equalTo("not_found"));
        // And in their own, only what is theirs.
        search(stranger, "quarterly").body("tasks.title", contains("My own quarterly review")).body("comments", empty());
        search(stranger, "secret").body("projects", empty());
    }

    @Test
    void aClientViewerOnlyFindsWhatBelongsToTheirClient() {
        Account owner = Api.signup("Bea");
        Account viewer = Api.signup("Cem");
        long acme = Api.createClient(owner, "Acme Harbour");
        long rival = Api.createClient(owner, "Rival Harbour");
        Api.addMember(owner, viewer, "CLIENT", acme);
        long theirs = Api.createProject(owner, "ACME-1", acme);
        long rivals = Api.createProject(owner, "RIVAL-1", rival);
        long internal = Api.createProject(owner, "HARBOUR-INT");
        long theirTask = Api.createTask(owner, theirs, Map.of("title", "Harbour map"));
        long rivalTask = Api.createTask(owner, rivals, Map.of("title", "Harbour pitch", "description", "harbour"));
        long internalTask = Api.createTask(owner, internal, Map.of("title", "Harbour pricing"));
        long theirComment = comment(owner, theirTask, "Harbour photos are in the shared folder");
        comment(owner, rivalTask, "Harbour budget is tight");
        comment(owner, internalTask, "Harbour margin is 40%");

        // The team finds all of it.
        search(owner, "harbour")
                .body("projects.id", containsInAnyOrder((int) theirs, (int) rivals, (int) internal))
                .body("tasks.id", containsInAnyOrder((int) theirTask, (int) rivalTask, (int) internalTask))
                .body("comments", hasSize(3));

        // The client viewer finds their own project, its task and its comment, and nothing says there is more.
        search(viewer, owner.orgId(), "harbour").statusCode(200)
                .body("projects.id", contains((int) theirs))
                .body("tasks.id", contains((int) theirTask))
                .body("comments.id", contains((int) theirComment));
        // Not through the name of another client, nor through a project code.
        search(viewer, owner.orgId(), "rival").statusCode(200).body("projects", empty()).body("tasks", empty()).body("comments", empty());
        search(viewer, owner.orgId(), "pricing").statusCode(200).body("tasks", empty());
    }

    @Test
    void aProjectThatLeftTheClientIsNoLongerFound() {
        Account owner = Api.signup("Dov");
        Account viewer = Api.signup("Eli");
        long clientId = Api.createClient(owner, "Lighthouse");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        long projectId = Api.createProject(owner, "LIGHT", clientId);
        Api.createTask(owner, projectId, Map.of("title", "Lighthouse survey"));
        search(viewer, owner.orgId(), "lighthouse").statusCode(200).body("projects", hasSize(1)).body("tasks", hasSize(1));

        owner.as().body(Map.of("name", "Project LIGHT", "code", "LIGHT", "status", "ACTIVE"))
                .put("/api/projects/" + projectId).then().statusCode(200).body("clientId", nullValue());
        search(viewer, owner.orgId(), "lighthouse").statusCode(200)
                .body("projects", empty()).body("tasks", empty()).body("comments", empty());
    }

    @Test
    void aClientViewerWhoseClientIsGoneFindsNothing() {
        Account owner = Api.signup("Fay");
        Account viewer = Api.signup("Gus");
        long clientId = Api.createClient(owner, "Gone Fishing");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        long internal = Api.createProject(owner, "FISH");
        Api.createTask(owner, internal, Map.of("title", "Fishing permit"));

        // Deleting the client leaves the viewer in the organisation, without a client. Projects
        // without a client are the internal ones, and those are not theirs to find.
        owner.as().delete("/api/orgs/" + owner.orgId() + "/clients/" + clientId).then().statusCode(204);
        search(viewer, owner.orgId(), "fish").statusCode(200)
                .body("projects", empty()).body("tasks", empty()).body("comments", empty());
        search(owner, "fish").body("projects", hasSize(1)).body("tasks", hasSize(1));
    }

    private static ValidatableResponse recent(Account who, long orgId, List<Map<String, Object>> picks) {
        return who.as().body(Map.of("picks", picks)).post("/api/orgs/" + orgId + "/search/recent").then();
    }

    @Test
    void recentPicksComeBackAsTheyAreNowAndOnlyWhenTheyCanStillBeOpened() {
        Account owner = Api.signup("Xia");
        long clientId = Api.createClient(owner, "Yarrow");
        long clientProject = Api.createProject(owner, "YAR", clientId);
        long internal = Api.createProject(owner, "INT");
        long clientTask = Api.createTask(owner, clientProject, Map.of("title", "Client brief"));
        long internalTask = Api.createTask(owner, internal, Map.of("title", "Internal budget"));
        long gone = Api.createTask(owner, internal, Map.of("title", "Soon deleted"));
        long remark = comment(owner, internalTask, "Keep this between us");
        Account member = Api.signup("Yan");
        Api.addMember(owner, member, "MEMBER");

        owner.as().body(Map.of("title", "Client brief, final")).patch("/api/tasks/" + clientTask).then().statusCode(200);
        owner.as().delete("/api/tasks/" + gone).then().statusCode(204);

        List<Map<String, Object>> picks = List.of(
                Map.of("type", "TASK", "id", internalTask), Map.of("type", "TASK", "id", gone),
                Map.of("type", "COMMENT", "id", remark), Map.of("type", "TASK", "id", clientTask),
                Map.of("type", "PROJECT", "id", clientProject));
        recent(member, owner.orgId(), picks).statusCode(200)
                .body("id", contains((int) internalTask, (int) remark, (int) clientTask, (int) clientProject))
                .body("[2].title", equalTo("Client brief, final"))
                .body("[1].title", equalTo("Internal budget"))
                .body("[1].snippet", nullValue());

        // The member is made a client viewer: the internal picks are no longer theirs to see.
        Number memberId = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().extract()
                .path("find { it.userId == " + member.userId() + " }.id");
        owner.as().body(Map.of("role", "CLIENT", "clientId", clientId))
                .patch("/api/orgs/" + owner.orgId() + "/members/" + memberId).then().statusCode(200);
        recent(member, owner.orgId(), picks).statusCode(200).body("id", contains((int) clientTask, (int) clientProject));

        // Somebody outside the organisation gets the same answer as for any other of its paths.
        recent(Api.signup("Zed"), owner.orgId(), picks).statusCode(404);
    }
}
