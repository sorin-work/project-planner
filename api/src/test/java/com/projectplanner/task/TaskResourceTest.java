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
        owner.as().get("/api/tasks/" + parent + "/subtasks").then().body("$", hasSize(1));
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
    void boardReorderPutsTheCardWhereItWasDropped() {
        Account owner = Api.signup("Pia");
        long projectId = Api.createProject(owner, "BOARD");
        long a = Api.createTask(owner, projectId, Map.of("title", "A"));
        long b = Api.createTask(owner, projectId, Map.of("title", "B"));
        long c = Api.createTask(owner, projectId, Map.of("title", "C"));
        owner.as().body(Map.of("position", 0)).patch("/api/tasks/" + c).then().statusCode(200);
        owner.as().get("/api/projects/" + projectId + "/tasks").then()
                .body("find { it.id == " + c + " }.position", equalTo(0))
                .body("find { it.id == " + a + " }.position", equalTo(1))
                .body("find { it.id == " + b + " }.position", equalTo(2));
    }

    @Test
    void assigningSomeoneNotifiesThem() {
        Account owner = Api.signup("Quin");
        Account member = Api.signup("Remy");
        Api.addMember(owner, member, "MEMBER");
        long projectId = Api.createProject(owner, "ASSIGN");
        long task = Api.createTask(owner, projectId, Map.of("title", "Write copy"));
        owner.as().body(Map.of("assigneeId", member.userId())).patch("/api/tasks/" + task).then().statusCode(200);
        member.as().get("/api/notifications").then()
                .body("[0].type", equalTo("ASSIGNED"))
                .body("[0].taskId", equalTo((int) task));
    }

    @Test
    void cannotAssignClientViewers() {
        Account owner = Api.signup("Sol");
        long clientId = Api.createClient(owner, "Client");
        Account viewer = Api.signup("Tia");
        Api.addMember(owner, viewer, "CLIENT", clientId);
        long projectId = Api.createProject(owner, "NOCLIENT", clientId);
        owner.as().body(Map.of("title", "x", "assigneeId", viewer.userId()))
                .post("/api/projects/" + projectId + "/tasks").then().statusCode(400).body("code", equalTo("unknown_assignee"));
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

        // A slips by a week, which is five working days.
        owner.as().body(Map.of("days", 5)).post("/api/tasks/" + a + "/shift")
                .then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-09"))
                .body("rescheduled.id", hasItems((int) b, (int) c));
        owner.as().get("/api/tasks/" + c).then().body("startDate", equalTo("2026-03-13"));
        owner.as().get("/api/tasks/" + b).then().body("startDate", equalTo("2026-03-11")).body("dueDate", equalTo("2026-03-12"));
    }

    @Test
    void shiftingCountsWorkingDaysInEitherDirection() {
        Account owner = Api.signup("Gus");
        long projectId = Api.createProject(owner, "SHIFT");
        long task = Api.createTask(owner, projectId, Map.of("title", "Build", "startDate", "2026-03-05", "dueDate", "2026-03-06"));
        long launch = Api.createTask(owner, projectId, Map.of("title", "Launch", "milestone", true, "dueDate", "2026-03-06"));
        long someday = Api.createTask(owner, projectId, Map.of("title", "Someday"));

        // Thursday to Friday, one column to the right: Friday to Monday, not Friday to Saturday.
        owner.as().body(Map.of("days", 1)).post("/api/tasks/" + task + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-06")).body("task.dueDate", equalTo("2026-03-09"));
        owner.as().body(Map.of("days", 1)).post("/api/tasks/" + task + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-09")).body("task.dueDate", equalTo("2026-03-10"));
        // A Monday bar moved left lands on Friday and stays there.
        owner.as().body(Map.of("days", -1)).post("/api/tasks/" + task + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-06")).body("task.dueDate", equalTo("2026-03-09"));
        owner.as().body(Map.of("days", 0)).post("/api/tasks/" + task + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-06")).body("task.dueDate", equalTo("2026-03-09"));

        owner.as().body(Map.of("days", 1)).post("/api/tasks/" + launch + "/shift").then().statusCode(200)
                .body("task.startDate", equalTo("2026-03-09")).body("task.dueDate", equalTo("2026-03-09"));
        owner.as().body(Map.of("days", 1)).post("/api/tasks/" + someday + "/shift").then().statusCode(400)
                .body("code", equalTo("unscheduled"));
    }

    @Test
    void criticalPath() {
        Account owner = Api.signup("Yan");
        long projectId = Api.createProject(owner, "CPM");
        long a = Api.createTask(owner, projectId, Map.of("title", "A", "startDate", "2026-03-02", "dueDate", "2026-03-03"));
        long b = Api.createTask(owner, projectId, Map.of("title", "B", "startDate", "2026-03-02", "dueDate", "2026-03-06"));
        long c = Api.createTask(owner, projectId, Map.of("title", "C", "startDate", "2026-03-09", "dueDate", "2026-03-09"));
        owner.as().body(Map.of("predecessorId", a)).post("/api/tasks/" + c + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", b)).post("/api/tasks/" + c + "/dependencies").then().statusCode(201);

        owner.as().get("/api/projects/" + projectId + "/critical-path").then().statusCode(200)
                .body("criticalTaskIds", equalTo(java.util.List.of((int) b, (int) c)))
                .body("finish", equalTo("2026-03-09"))
                .body("tasks.find { it.taskId == " + a + " }.floatDays", equalTo(3));
    }

    @Test
    void aTaskMovedBeforeItsPredecessorIsDueIsReportedWithTheDatesThatFixIt() {
        Account owner = Api.signup("Abe");
        long projectId = Api.createProject(owner, "BROKEN");
        long design = Api.createTask(owner, projectId, Map.of("title", "Design", "startDate", "2026-03-02", "dueDate", "2026-03-06"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "startDate", "2026-03-09", "dueDate", "2026-03-11"));
        owner.as().body(Map.of("predecessorId", design)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200).body("$", hasSize(0));

        // Build is dragged into the week Design is still running. Nothing stops that.
        owner.as().body(Map.of("startDate", "2026-03-04", "dueDate", "2026-03-06")).patch("/api/tasks/" + build)
                .then().statusCode(200).body("rescheduled", hasSize(0));
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].type", equalTo("STARTS_TOO_EARLY"))
                .body("[0].taskId", equalTo((int) build))
                .body("[0].relatedTaskId", equalTo((int) design))
                .body("[0].days", equalTo(3))
                .body("[0].suggestedStart", equalTo("2026-03-09"))
                .body("[0].suggestedDue", equalTo("2026-03-11"))
                .body("[0].suggestionCauses", nullValue());
        // The critical path counts Build from the day it can start, so it ends after the latest due date.
        owner.as().get("/api/projects/" + projectId + "/critical-path").then().body("finish", equalTo("2026-03-11"));
        owner.as().get("/api/projects/" + projectId + "/health").then().body("forecastEnd", equalTo("2026-03-06"));

        // "Move to" sends the two suggested dates back as they are.
        owner.as().body(Map.of("startDate", "2026-03-09", "dueDate", "2026-03-11")).patch("/api/tasks/" + build)
                .then().statusCode(200);
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().body("$", hasSize(0));
        owner.as().get("/api/projects/" + projectId + "/critical-path").then().body("finish", equalTo("2026-03-11"));
        owner.as().get("/api/projects/" + projectId + "/health").then().body("forecastEnd", equalTo("2026-03-11"));
    }

    @Test
    void scheduleIssuesCoverUndatedPredecessorsTheProjectEndAndSubtasks() {
        Account owner = Api.signup("Bea");
        Number id = owner.as().body(Map.of("name", "Launch", "code", "ENDS", "status", "ACTIVE",
                        "startDate", "2026-03-02", "endDate", "2026-03-13"))
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        long projectId = id.longValue();
        long research = Api.createTask(owner, projectId, Map.of("title", "Research"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "startDate", "2026-03-09", "dueDate", "2026-03-17"));
        owner.as().body(Map.of("predecessorId", research)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        long endpoints = Api.createTask(owner, projectId, Map.of("title", "Endpoints", "parentId", build,
                "startDate", "2026-03-05", "dueDate", "2026-03-10"));
        // Finished work is history, however late it was.
        Api.createTask(owner, projectId, Map.of("title", "Kick-off", "status", "DONE", "startDate", "2026-03-16", "dueDate", "2026-03-20"));

        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200)
                .body("type", equalTo(java.util.List.of("OUTSIDE_PARENT", "WAITS_FOR_UNSCHEDULED", "AFTER_PROJECT_END")))
                .body("taskId", equalTo(java.util.List.of((int) endpoints, (int) build, (int) build)))
                .body("[0].relatedTaskId", equalTo((int) build))
                .body("[0].days", equalTo(2))
                .body("[1].relatedTaskId", equalTo((int) research))
                .body("[1].days", nullValue())
                .body("[2].relatedTaskId", nullValue())
                .body("[2].days", equalTo(2))
                .body("[2].suggestedStart", nullValue());

        // A finished task without dates holds nobody up.
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + research).then().statusCode(200);
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then()
                .body("type", equalTo(java.util.List.of("OUTSIDE_PARENT", "AFTER_PROJECT_END")));
    }

    @Test
    void theSuggestedDatesSayWhatTheyWouldCause() {
        Account owner = Api.signup("Hal");
        Number id = owner.as().body(Map.of("name", "Tight", "code", "TIGHT", "status", "ACTIVE",
                        "startDate", "2026-03-02", "endDate", "2026-03-13"))
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        long projectId = id.longValue();
        long design = Api.createTask(owner, projectId, Map.of("title", "Design", "startDate", "2026-03-09", "dueDate", "2026-03-13"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "startDate", "2026-03-16", "dueDate", "2026-03-18"));
        owner.as().body(Map.of("predecessorId", design)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        // Build is pulled back inside the project, in front of Design.
        owner.as().body(Map.of("startDate", "2026-03-11", "dueDate", "2026-03-13")).patch("/api/tasks/" + build).then().statusCode(200);

        // The way out of the broken link leads past the project's end, and the API says so.
        owner.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].type", equalTo("STARTS_TOO_EARLY"))
                .body("[0].suggestedStart", equalTo("2026-03-16"))
                .body("[0].suggestedDue", equalTo("2026-03-18"))
                .body("[0].suggestionCauses", equalTo(java.util.List.of("AFTER_PROJECT_END")));
    }

    @Test
    void scheduleIssuesAreForPeopleWhoCanSeeTheProject() {
        Account owner = Api.signup("Cy");
        long acme = Api.createClient(owner, "Acme");
        long globex = Api.createClient(owner, "Globex");
        long projectId = Api.createProject(owner, "SEE", acme);
        Account viewer = Api.signup("Dee");
        Api.addMember(owner, viewer, "CLIENT", acme);
        Account otherClient = Api.signup("Eli");
        Api.addMember(owner, otherClient, "CLIENT", globex);
        Account stranger = Api.signup("Fay");

        viewer.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200).body("$", hasSize(0));
        otherClient.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(404);
        stranger.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(404);
        io.restassured.RestAssured.given().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(401);
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
