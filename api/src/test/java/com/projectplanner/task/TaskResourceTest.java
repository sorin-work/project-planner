package com.projectplanner.task;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class TaskResourceTest {

    // 2026-03-02 is a Monday.

    @Test
    void createsTasksAndSubtasks() {
        Account owner = Api.signup("Kai");
        long projectId = Api.createProject(owner, "TSK");
        long parent = Api.createTask(owner, projectId, Map.of("title", "Build API", "estimateHours", 16,
                "startDate", "2026-03-02", "dueDate", "2026-03-06", "assigneeId", owner.userId(), "priority", "HIGH"));
        owner.as().body(Map.of("title", "Endpoints", "parentId", parent))
                .post("/api/projects/" + projectId + "/tasks").then().statusCode(201).body("parentId", equalTo((int) parent));

        owner.as().get("/api/tasks/" + parent).then().statusCode(200)
                .body("subtaskCount", equalTo(1))
                .body("assigneeName", equalTo("Kai"))
                .body("priority", equalTo("HIGH"));
    }

    @Test
    void subtasksCannotNest() {
        Account owner = Api.signup("Lia");
        long projectId = Api.createProject(owner, "NEST");
        long parent = Api.createTask(owner, projectId, Map.of("title", "Parent"));
        long child = Api.createTask(owner, projectId, Map.of("title", "Child", "parentId", parent));
        owner.as().body(Map.of("title", "Grandchild", "parentId", child))
                .post("/api/projects/" + projectId + "/tasks").then().statusCode(400).body("code", equalTo("nested_subtask"));
    }

    @Test
    void rejectsDueBeforeStart() {
        Account owner = Api.signup("Max");
        long projectId = Api.createProject(owner, "DATES");
        owner.as().body(Map.of("title", "Backwards", "startDate", "2026-03-06", "dueDate", "2026-03-02"))
                .post("/api/projects/" + projectId + "/tasks").then().statusCode(400).body("code", equalTo("invalid_dates"));
    }

    @Test
    void milestonesAreSingleDays() {
        Account owner = Api.signup("Nia");
        long projectId = Api.createProject(owner, "MS");
        owner.as().body(Map.of("title", "Launch", "milestone", true, "dueDate", "2026-03-20"))
                .post("/api/projects/" + projectId + "/tasks").then().statusCode(201)
                .body("startDate", equalTo("2026-03-20"))
                .body("dueDate", equalTo("2026-03-20"));
    }

    @Test
    void statusChangesTrackCompletion() {
        Account owner = Api.signup("Oli");
        long projectId = Api.createProject(owner, "DONE");
        long task = Api.createTask(owner, projectId, Map.of("title", "Ship it"));
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + task)
                .then().statusCode(200).body("task.completedAt", notNullValue());
        owner.as().body(Map.of("status", "IN_PROGRESS")).patch("/api/tasks/" + task)
                .then().statusCode(200).body("task.completedAt", nullValue());
        owner.as().get("/api/projects/" + projectId + "/activity").then()
                .body("action", hasItems("task.created", "task.status"));
    }

    @Test
    void rejectsDependencyCycles() {
        Account owner = Api.signup("Ugo");
        long projectId = Api.createProject(owner, "CYCLE");
        long a = Api.createTask(owner, projectId, Map.of("title", "Design"));
        long b = Api.createTask(owner, projectId, Map.of("title", "Build"));
        long c = Api.createTask(owner, projectId, Map.of("title", "Test"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", b)).post("/api/tasks/" + c + "/dependencies").then().statusCode(201);

        owner.as().body(Map.of("predecessorId", c)).post("/api/tasks/" + a + "/dependencies")
                .then().statusCode(409)
                .body("code", equalTo("dependency_cycle"))
                .body("message", containsString("Design → Build → Test → Design"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + a + "/dependencies")
                .then().statusCode(409).body("code", equalTo("dependency_cycle"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies")
                .then().statusCode(409).body("code", equalTo("dependency_exists"));
    }

    @Test
    void dependenciesMustStayInTheProject() {
        Account owner = Api.signup("Vic");
        long p1 = Api.createProject(owner, "P1");
        long p2 = Api.createProject(owner, "P2");
        long a = Api.createTask(owner, p1, Map.of("title", "A"));
        long b = Api.createTask(owner, p2, Map.of("title", "B"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies")
                .then().statusCode(400).body("code", equalTo("unknown_task"));
    }

    @Test
    void addingADependencyPushesTheSuccessor() {
        Account owner = Api.signup("Wes");
        long projectId = Api.createProject(owner, "PUSH");
        long design = Api.createTask(owner, projectId, Map.of("title", "Design", "startDate", "2026-03-02", "dueDate", "2026-03-04"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "startDate", "2026-03-03", "dueDate", "2026-03-05"));
        owner.as().body(Map.of("predecessorId", design, "lagDays", 1)).post("/api/tasks/" + build + "/dependencies")
                .then().statusCode(201)
                .body("rescheduled", hasSize(1))
                .body("rescheduled[0].id", equalTo((int) build))
                .body("rescheduled[0].startDate", equalTo("2026-03-06"))
                .body("rescheduled[0].dueDate", equalTo("2026-03-10"));
    }

    @Test
    void movingATaskCascadesThroughItsDependants() {
        Account owner = Api.signup("Xia");
        long projectId = Api.createProject(owner, "CASCADE");
        long a = Api.createTask(owner, projectId, Map.of("title", "A", "startDate", "2026-03-02", "dueDate", "2026-03-03"));
        long b = Api.createTask(owner, projectId, Map.of("title", "B", "startDate", "2026-03-04", "dueDate", "2026-03-05"));
        long c = Api.createTask(owner, projectId, Map.of("title", "C", "startDate", "2026-03-06", "dueDate", "2026-03-06"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", b)).post("/api/tasks/" + c + "/dependencies").then().statusCode(201);

        // A slips by a week.
        owner.as().body(Map.of("days", 7)).post("/api/tasks/" + a + "/shift")
                .then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-09"))
                .body("rescheduled.id", hasItems((int) b, (int) c));
        owner.as().get("/api/tasks/" + c).then().body("startDate", equalTo("2026-03-13"));
        owner.as().get("/api/tasks/" + b).then().body("startDate", equalTo("2026-03-11")).body("dueDate", equalTo("2026-03-12"));
    }

    @Test
    void deletingATaskRemovesItsLinks() {
        Account owner = Api.signup("Zed");
        long projectId = Api.createProject(owner, "DEL");
        long a = Api.createTask(owner, projectId, Map.of("title", "A"));
        long b = Api.createTask(owner, projectId, Map.of("title", "B"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + b + "/dependencies").then().statusCode(201);
        owner.as().delete("/api/tasks/" + a).then().statusCode(204);
        owner.as().get("/api/projects/" + projectId + "/dependencies").then().body("$", hasSize(0));
        owner.as().get("/api/tasks/" + a).then().statusCode(404);
        owner.as().get("/api/projects/" + projectId + "/tasks").then().body("title", hasItem("B"));
    }
}
