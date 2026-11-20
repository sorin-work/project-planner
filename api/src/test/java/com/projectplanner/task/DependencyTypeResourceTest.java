package com.projectplanner.task;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class DependencyTypeResourceTest {

    // 2026-03-02 is a Monday.

    private static long task(Account owner, long projectId, String title, String start, String due) {
        return Api.createTask(owner, projectId, Map.of("title", title, "startDate", start, "dueDate", due));
    }

    @Test
    void aLinkHasATypeAndALeadAndBothCanBeChangedLater() {
        Account owner = Api.signup("Ada");
        long projectId = Api.createProject(owner, "TYPES");
        long dev = task(owner, projectId, "Development", "2026-03-02", "2026-03-13");
        long qa = task(owner, projectId, "QA", "2026-03-11", "2026-03-17");

        // QA starts two working days before development ends: that holds where QA is, so nothing moves.
        owner.as().body(Map.of("predecessorId", dev, "lagDays", -3, "type", "FS")).post("/api/tasks/" + qa + "/dependencies")
                .then().statusCode(201).body("task.predecessors[0].type", equalTo("FS")).body("task.predecessors[0].lagDays", equalTo(-3))
                .body("rescheduled", hasSize(0));
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().body("$", hasSize(0));

        // A shorter lead pushes QA to the Thursday, keeping its five working days.
        Number linkId = owner.as().get("/api/projects/" + projectId + "/dependencies").then().extract().path("[0].id");
        owner.as().body(Map.of("lagDays", -2)).patch("/api/dependencies/" + linkId).then().statusCode(200)
                .body("rescheduled[0].startDate", equalTo("2026-03-12")).body("rescheduled[0].dueDate", equalTo("2026-03-18"));

        // Finish-to-finish: QA ends no earlier than a day after development, which it already does.
        owner.as().body(Map.of("type", "FF", "lagDays", 1)).patch("/api/dependencies/" + linkId).then().statusCode(200)
                .body("task.predecessors[0].type", equalTo("FF")).body("rescheduled", hasSize(0));
        owner.as().get("/api/projects/" + projectId + "/dependencies").then()
                .body("[0].type", equalTo("FF")).body("[0].lagDays", equalTo(1));
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().body("$", hasSize(0));

        // Left out, the type is finish-to-start.
        long docs = task(owner, projectId, "Docs", "2026-03-20", "2026-03-20");
        owner.as().body(Map.of("predecessorId", qa)).post("/api/tasks/" + docs + "/dependencies").then().statusCode(201)
                .body("task.predecessors[0].type", equalTo("FS")).body("task.predecessors[0].lagDays", equalTo(0));

        // A copy of the project keeps the types and the leads, and has the same issues: none.
        Number copy = owner.as().body(Map.of("name", "Types again", "code", "TYPES-2", "startDate", "2026-06-01"))
                .post("/api/projects/" + projectId + "/duplicate").then().statusCode(201).extract().path("id");
        owner.as().get("/api/projects/" + copy + "/dependencies").then()
                .body("type.sort()", equalTo(java.util.List.of("FF", "FS"))).body("lagDays.sort()", equalTo(java.util.List.of(0, 1)));
        owner.as().get("/api/projects/" + copy + "/schedule-issues").then().body("$", hasSize(0));
    }

    @Test
    void aStartToStartLinkFollowsThePredecessorsStartAndIsNotReportedWhereItHolds() {
        Account owner = Api.signup("Bea");
        long projectId = Api.createProject(owner, "SS");
        long design = task(owner, projectId, "Design", "2026-03-02", "2026-03-13");
        long copy = task(owner, projectId, "Copy", "2026-03-03", "2026-03-04");
        owner.as().body(Map.of("predecessorId", design, "lagDays", 1, "type", "SS")).post("/api/tasks/" + copy + "/dependencies")
                .then().statusCode(201).body("rescheduled", hasSize(0));
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().body("$", hasSize(0));

        // Design starts a week later: Copy follows its start, not its end.
        owner.as().body(Map.of("startDate", "2026-03-09")).patch("/api/tasks/" + design).then().statusCode(200)
                .body("rescheduled[0].startDate", equalTo("2026-03-10")).body("rescheduled[0].dueDate", equalTo("2026-03-11"));
    }

    @Test
    void aLoopIsALoopWhateverTheTypes() {
        Account owner = Api.signup("Cal");
        long projectId = Api.createProject(owner, "LOOP");
        long a = task(owner, projectId, "A", "2026-03-02", "2026-03-03");
        long b = task(owner, projectId, "B", "2026-03-02", "2026-03-03");
        owner.as().body(Map.of("predecessorId", a, "type", "SS")).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", b, "type", "FF")).post("/api/tasks/" + a + "/dependencies").then().statusCode(409)
                .body("code", equalTo("dependency_cycle"));
    }

    @Test
    void theLagIsBoundedAndTheTypeIsOneOfTheFour() {
        Account owner = Api.signup("Dot");
        long projectId = Api.createProject(owner, "BOUND");
        long a = task(owner, projectId, "A", "2026-03-02", "2026-03-03");
        long b = task(owner, projectId, "B", "2026-03-04", "2026-03-05");
        owner.as().body(Map.of("predecessorId", a, "lagDays", -366)).post("/api/tasks/" + b + "/dependencies").then().statusCode(400)
                .body("errors.field", hasItem("lagDays"));
        owner.as().body(Map.of("predecessorId", a, "lagDays", 366)).post("/api/tasks/" + b + "/dependencies").then().statusCode(400);
        owner.as().body(Map.of("predecessorId", a, "type", "XX")).post("/api/tasks/" + b + "/dependencies").then().statusCode(400);
        owner.as().body(Map.of("predecessorId", a, "lagDays", -365)).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        Number linkId = owner.as().get("/api/projects/" + projectId + "/dependencies").then().extract().path("[0].id");
        owner.as().body(Map.of("lagDays", 400)).patch("/api/dependencies/" + linkId).then().statusCode(400);
    }

    @Test
    void onlyTheTeamChangesALinkAndOnlyInItsOwnOrganisation() {
        Account owner = Api.signup("Eve");
        long clientId = Api.createClient(owner, "Client");
        long projectId = Api.createProject(owner, "WHO", clientId);
        long a = task(owner, projectId, "A", "2026-03-02", "2026-03-03");
        long b = task(owner, projectId, "B", "2026-03-04", "2026-03-05");
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        Number linkId = owner.as().get("/api/projects/" + projectId + "/dependencies").then().extract().path("[0].id");

        Account viewer = Api.signup("Fin");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        viewer.as().body(Map.of("type", "SS")).patch("/api/dependencies/" + linkId).then().statusCode(403);
        Account stranger = Api.signup("Gil");
        stranger.as().body(Map.of("type", "SS")).patch("/api/dependencies/" + linkId).then().statusCode(404);
        owner.as().body(Map.of("type", "SS")).patch("/api/dependencies/999999999").then().statusCode(404);
        owner.as().get("/api/projects/" + projectId + "/dependencies").then().body("[0].type", equalTo("FS"));
    }
}
