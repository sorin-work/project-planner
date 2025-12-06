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
    void statusChangesTrackCompletion() {
        Account owner = Api.signup("Oli");
        long projectId = Api.createProject(owner, "DONE");
        long task = Api.createTask(owner, projectId, Map.of("title", "Ship it"));
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + task)
                .then().statusCode(200).body("completedAt", notNullValue());
        owner.as().body(Map.of("status", "IN_PROGRESS")).patch("/api/tasks/" + task)
                .then().statusCode(200).body("completedAt", nullValue());
        owner.as().get("/api/projects/" + projectId + "/activity").then()
                .body("action", hasItems("task.created", "task.status"));
    }
}
