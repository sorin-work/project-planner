package com.projectplanner.project;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskDependency;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ProjectDuplicateTest {

    // 2026-03-02 is a Monday, 2026-06-10 a Wednesday.

    private static final WorkCalendar CAL = WorkCalendar.DEFAULT;

    @Inject
    EntityManager em;

    /** A small website project: three phases, a parent with two subtasks, a milestone, a task without dates and two links. */
    private record Website(long projectId, long clientId, long design, long build, long endpoints, long screens, long launch, long someday) {
    }

    private static Website website(Account owner, Account designer, String code) {
        Api.addMember(owner, designer, "MEMBER");
        long clientId = Api.createClient(owner, "Client of " + code);
        Map<String, Object> project = new HashMap<>(Map.of("name", "Website", "code", code, "clientId", clientId, "status", "ACTIVE",
                "startDate", "2026-03-02", "endDate", "2026-03-20"));
        project.putAll(Map.of("budgetHours", 200, "budgetAmount", 16000, "hourlyRate", 90, "color", "#2f9e72", "description", "The original"));
        Number id = owner.as().body(project)
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        long projectId = id.longValue();
        long designPhase = phase(owner, projectId, "Design");
        long buildPhase = phase(owner, projectId, "Build");
        long launchPhase = phase(owner, projectId, "Launch");
        // The settings tab leaves positions like these behind: a repeat and a gap. The order is Launch, Design, Build.
        owner.as().body(Map.of("name", "Design", "position", 7)).put("/api/phases/" + designPhase).then().statusCode(200);
        owner.as().body(Map.of("name", "Build", "position", 7)).put("/api/phases/" + buildPhase).then().statusCode(200);
        owner.as().body(Map.of("name", "Launch", "position", 3)).put("/api/phases/" + launchPhase).then().statusCode(200);

        long design = Api.createTask(owner, projectId, Map.of("title", "Design", "phaseId", designPhase, "estimateHours", 20,
                "priority", "HIGH", "description", "Wireframes first", "assigneeId", designer.userId(),
                "startDate", "2026-03-02", "dueDate", "2026-03-06"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "phaseId", buildPhase, "estimateHours", 40,
                "assigneeId", owner.userId(), "startDate", "2026-03-09", "dueDate", "2026-03-13"));
        long endpoints = Api.createTask(owner, projectId, Map.of("title", "Endpoints", "parentId", build, "estimateHours", 8,
                "startDate", "2026-03-09", "dueDate", "2026-03-10"));
        long screens = Api.createTask(owner, projectId, Map.of("title", "Screens", "parentId", build,
                "startDate", "2026-03-11", "dueDate", "2026-03-13"));
        long launch = Api.createTask(owner, projectId, Map.of("title", "Launch", "phaseId", launchPhase, "milestone", true,
                "dueDate", "2026-03-18"));
        long someday = Api.createTask(owner, projectId, Map.of("title", "Someday"));
        owner.as().body(Map.of("predecessorId", design)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", build, "lagDays", 2)).post("/api/tasks/" + launch + "/dependencies").then().statusCode(201);
        return new Website(projectId, clientId, design, build, endpoints, screens, launch, someday);
    }

    private static long phase(Account owner, long projectId, String name) {
        Number id = owner.as().body(Map.of("name", name)).post("/api/projects/" + projectId + "/phases")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static long duplicate(Account who, long projectId, Map<String, Object> body) {
        Number id = who.as().body(body).post("/api/projects/" + projectId + "/duplicate").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static JsonPath tasks(Account who, long projectId) {
        return who.as().get("/api/projects/" + projectId + "/tasks").then().statusCode(200).extract().jsonPath();
    }

    private static String task(String title) {
        return "find { it.title == '" + title + "' }";
    }

    @Test
    void copiesThePlanAndMovesItToTheNewStart() {
        Account owner = Api.signup("Ada");
        Account designer = Api.signup("Bea");
        Website source = website(owner, designer, "WEB");
        // What happened in the source: work was done, time logged, things said.
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + source.design()).then().statusCode(200);
        owner.as().body(Map.of("status", "IN_PROGRESS")).patch("/api/tasks/" + source.build()).then().statusCode(200);
        owner.as().body(Map.of("projectId", source.projectId(), "taskId", source.build(), "date", "2026-03-10", "minutes", 120))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        owner.as().body(Map.of("body", "Looks good")).post("/api/tasks/" + source.design() + "/comments").then().statusCode(201);
        JsonPath before = tasks(owner, source.projectId());
        int designerNotifications = designer.as().get("/api/notifications").then().extract().jsonPath().getList("$").size();

        long copyId = duplicate(owner, source.projectId(), Map.of("name", " Website for Globex ", "code", "glx-web", "startDate", "2026-06-10"));

        // The project: planned, internal unless a client is named, with the settings of the source and its dates moved.
        owner.as().get("/api/projects/" + copyId).then().statusCode(200)
                .body("name", equalTo("Website for Globex"))
                .body("code", equalTo("GLX-WEB"))
                .body("status", equalTo("PLANNED"))
                .body("clientId", nullValue())
                .body("description", equalTo("The original"))
                .body("color", equalTo("#2f9e72"))
                .body("budgetHours", Api.num(200))
                .body("budgetAmount", Api.num(16000))
                .body("hourlyRate", Api.num(90))
                .body("startDate", equalTo("2026-06-10"))
                .body("endDate", equalTo("2026-06-30"));

        // Phases in the order they had, numbered from zero.
        owner.as().get("/api/projects/" + copyId + "/phases").then().statusCode(200)
                .body("name", equalTo(List.of("Launch", "Design", "Build")))
                .body("position", equalTo(List.of(0, 1, 2)));
        JsonPath phases = owner.as().get("/api/projects/" + copyId + "/phases").then().extract().jsonPath();

        // Tasks: the same distance from the first day and the same length, both in working days.
        JsonPath copy = tasks(owner, copyId);
        assertThat(copy.getList("title", String.class)).containsExactlyInAnyOrder("Design", "Build", "Endpoints", "Screens", "Launch", "Someday");
        assertThat(dates(copy, "Design")).isEqualTo("2026-06-10..2026-06-16");
        assertThat(dates(copy, "Build")).isEqualTo("2026-06-17..2026-06-23");
        assertThat(dates(copy, "Endpoints")).isEqualTo("2026-06-17..2026-06-18");
        assertThat(dates(copy, "Screens")).isEqualTo("2026-06-19..2026-06-23");
        assertThat(dates(copy, "Launch")).isEqualTo("2026-06-26..2026-06-26");
        assertThat(dates(copy, "Someday")).isEqualTo("null..null");

        // Everything starts again, nobody is assigned unless asked for, and the plan's own details are kept.
        assertThat(copy.getList("status", String.class)).containsOnly("TODO");
        assertThat(copy.getList("completedAt")).containsOnlyNulls();
        assertThat(copy.getList("assigneeId")).containsOnlyNulls();
        assertThat(copy.getList("loggedHours", Float.class)).containsOnly(0f);
        assertThat(copy.getString(task("Design") + ".priority")).isEqualTo("HIGH");
        assertThat(copy.getString(task("Design") + ".description")).isEqualTo("Wireframes first");
        assertThat(copy.getFloat(task("Design") + ".estimateHours")).isEqualTo(20f);
        assertThat(copy.getFloat(task("Endpoints") + ".estimateHours")).isEqualTo(8f);
        assertThat(copy.getBoolean(task("Launch") + ".milestone")).isTrue();
        assertThat(copy.getBoolean(task("Build") + ".milestone")).isFalse();

        // Subtasks hang under the copy of their parent, tasks sit in the copy of their phase.
        long newBuild = copy.getLong(task("Build") + ".id");
        assertThat(newBuild).isNotEqualTo(source.build());
        assertThat(copy.getLong(task("Endpoints") + ".parentId")).isEqualTo(newBuild);
        assertThat(copy.getLong(task("Screens") + ".parentId")).isEqualTo(newBuild);
        assertThat(copy.getInt(task("Build") + ".subtaskCount")).isEqualTo(2);
        assertThat(copy.getLong(task("Build") + ".phaseId")).isEqualTo(phases.getLong("find { it.name == 'Build' }.id"));
        assertThat(copy.getLong(task("Launch") + ".phaseId")).isEqualTo(phases.getLong("find { it.name == 'Launch' }.id"));
        assertThat((Object) copy.get(task("Someday") + ".phaseId")).isNull();

        // The board order: the source's list order, numbered anew, because every card is in one column now.
        List<String> sourceOrder = before.getList("findAll { !it.parentId }.title", String.class);
        assertThat(copy.getList("findAll { !it.parentId }.sort { it.position }.title", String.class)).isEqualTo(sourceOrder);
        assertThat(copy.getList("findAll { !it.parentId }.position.sort()", Integer.class)).containsExactly(0, 1, 2, 3);
        assertThat(copy.getList("findAll { it.parentId }.sort { it.position }.title", String.class)).containsExactly("Endpoints", "Screens");

        // The links, with their lag, between the copies.
        long newDesign = copy.getLong(task("Design") + ".id");
        long newLaunch = copy.getLong(task("Launch") + ".id");
        owner.as().get("/api/projects/" + copyId + "/dependencies").then().statusCode(200)
                .body("$", hasSize(2))
                .body("find { it.successorId == " + newBuild + " }.predecessorId", equalTo((int) newDesign))
                .body("find { it.successorId == " + newBuild + " }.lagDays", equalTo(0))
                .body("find { it.successorId == " + newLaunch + " }.predecessorId", equalTo((int) newBuild))
                .body("find { it.successorId == " + newLaunch + " }.lagDays", equalTo(2));
        owner.as().get("/api/projects/" + copyId + "/schedule-issues").then().body("$", hasSize(0));

        // What happened in the source stays there. The copy has one line of history, and nobody was notified.
        owner.as().get("/api/tasks/" + newDesign + "/comments").then().body("$", hasSize(0));
        owner.as().get("/api/projects/" + copyId + "/activity").then()
                .body("$", hasSize(1))
                .body("[0].action", equalTo("project.duplicated"))
                .body("[0].summary", equalTo("Copied the plan of Website into Website for Globex: 3 phases, 6 tasks and 2 links"));
        assertThat(designer.as().get("/api/notifications").then().extract().jsonPath().getList("$")).hasSize(designerNotifications);
        JsonPath after = tasks(owner, source.projectId());
        assertThat(after.getList("status")).isEqualTo(before.getList("status"));
        assertThat(after.getList("startDate")).isEqualTo(before.getList("startDate"));
        assertThat(after.getList("position")).isEqualTo(before.getList("position"));
        assertThat(after.getFloat(task("Build") + ".loggedHours")).isEqualTo(2f);
        owner.as().get("/api/projects/" + source.projectId()).then().body("status", equalTo("ACTIVE")).body("clientId", notNullValue());
    }

    private static String dates(JsonPath tasks, String title) {
        return tasks.getString(task(title) + ".startDate") + ".." + tasks.getString(task(title) + ".dueDate");
    }

    @Test
    void keepsAssigneesAndTheClientOnlyWhenAsked() {
        Account owner = Api.signup("Cal");
        Account designer = Api.signup("Dot");
        Website source = website(owner, designer, "KEEP");
        int notifications = designer.as().get("/api/notifications").then().extract().jsonPath().getList("$").size();

        long kept = duplicate(owner, source.projectId(), Map.of("name", "Kept", "code", "KEEP-2", "startDate", "2026-06-10",
                "keepAssignees", true, "clientId", source.clientId()));
        owner.as().get("/api/projects/" + kept).then().body("clientId", equalTo((int) source.clientId())).body("clientName", equalTo("Client of KEEP"));
        JsonPath copy = tasks(owner, kept);
        assertThat(copy.getLong(task("Design") + ".assigneeId")).isEqualTo(designer.userId());
        assertThat(copy.getString(task("Design") + ".assigneeName")).isEqualTo("Dot");
        assertThat(copy.getLong(task("Build") + ".assigneeId")).isEqualTo(owner.userId());
        assertThat((Object) copy.get(task("Launch") + ".assigneeId")).isNull();
        // Forty tasks would be forty notifications. A copy sends none.
        assertThat(designer.as().get("/api/notifications").then().extract().jsonPath().getList("$")).hasSize(notifications);

        // The designer leaves. Her tasks are copied without her instead of the copy failing.
        Number membership = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then().extract()
                .path("find { it.userId == " + designer.userId() + " }.id");
        owner.as().delete("/api/orgs/" + owner.orgId() + "/members/" + membership).then().statusCode(204);
        long later = duplicate(owner, source.projectId(), Map.of("name", "Later", "code", "KEEP-3", "startDate", "2026-06-10", "keepAssignees", true));
        copy = tasks(owner, later);
        assertThat((Object) copy.get(task("Design") + ".assigneeId")).isNull();
        assertThat(copy.getLong(task("Build") + ".assigneeId")).isEqualTo(owner.userId());
    }

    @Test
    void aProjectWithNothingInItCopiesTooAndStartsOnTheDayAskedFor() {
        Account owner = Api.signup("Uma");
        long empty = Api.createProject(owner, "EMPTY");
        // A Saturday: the copy starts on the Monday after. There is no end date to move.
        long copy = duplicate(owner, empty, Map.of("name", "Still empty", "code", "EMPTY-2", "startDate", "2026-06-13"));
        owner.as().get("/api/projects/" + copy).then().statusCode(200)
                .body("startDate", equalTo("2026-06-15")).body("endDate", nullValue()).body("status", equalTo("PLANNED"));
        owner.as().get("/api/projects/" + copy + "/tasks").then().body("$", hasSize(0));
        owner.as().get("/api/projects/" + copy + "/activity").then()
                .body("[0].summary", equalTo("Copied the plan of Project EMPTY into Still empty: 0 phases, 0 tasks and 0 links"));

        // Tasks without any dates stay without, and the project still gets its start.
        long undated = Api.createProject(owner, "UNDATED");
        Api.createTask(owner, undated, Map.of("title", "Sometime"));
        long second = duplicate(owner, undated, Map.of("name", "Undated again", "code", "UNDATED-2", "startDate", "2026-06-10"));
        owner.as().get("/api/projects/" + second).then().body("startDate", equalTo("2026-06-10"));
        owner.as().get("/api/projects/" + second + "/tasks").then()
                .body("$", hasSize(1)).body("[0].startDate", nullValue()).body("[0].dueDate", nullValue());
    }

    @Test
    void proposesAFreeCodeAndRefusesOneThatIsTaken() {
        Account owner = Api.signup("Eve");
        Account designer = Api.signup("Fay");
        Website source = website(owner, designer, "CODE");

        owner.as().get("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(200)
                .body("name", equalTo("Website (copy)"))
                .body("code", equalTo("CODE-2"))
                .body("startDate", notNullValue())
                .body("phases", equalTo(3)).body("tasks", equalTo(6)).body("dependencies", equalTo(2));

        Map<String, Object> body = new HashMap<>(Map.of("name", "Website (copy)", "code", "code", "startDate", "2026-06-10"));
        owner.as().body(body).post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(409)
                .body("code", equalTo("code_taken"));
        body.put("code", "CODE-2");
        long copy = duplicate(owner, source.projectId(), body);
        owner.as().body(body).post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(409)
                .body("code", equalTo("code_taken"));
        // The next proposal counts on, from the source and from the copy alike.
        owner.as().get("/api/projects/" + source.projectId() + "/duplicate").then().body("code", equalTo("CODE-3"));
        owner.as().get("/api/projects/" + copy + "/duplicate").then().body("code", equalTo("CODE-3")).body("name", equalTo("Website (copy) (copy)"));

        // Refusals leave nothing behind.
        owner.as().body(Map.of("name", "No start", "code", "CODE-9")).post("/api/projects/" + source.projectId() + "/duplicate")
                .then().statusCode(400).body("errors.field", hasItem("startDate"));
        owner.as().body(Map.of("name", "Bad code", "code", "no spaces please", "startDate", "2026-06-10"))
                .post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(400).body("errors.field", hasItem("code"));
        Account stranger = Api.signup("Gil");
        long foreignClient = Api.createClient(stranger, "Elsewhere");
        owner.as().body(Map.of("name", "Their client", "code", "CODE-9", "startDate", "2026-06-10", "clientId", foreignClient))
                .post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(400).body("code", equalTo("unknown_client"));
        owner.as().get("/api/orgs/" + owner.orgId() + "/projects").then().body("project.code", equalTo(List.of("CODE", "CODE-2")));
    }

    @Test
    void onlyManagersWhoSeeTheSourceMayCopyIt() {
        Account owner = Api.signup("Hal");
        Account designer = Api.signup("Ivy");
        Account manager = Api.signup("Jon");
        Api.addMember(owner, manager, "MANAGER");
        Website source = website(owner, designer, "PERM");
        Account viewer = Api.signup("Kit");
        Api.addMember(owner, viewer, "CLIENT", source.clientId());
        Account otherViewer = Api.signup("Lou");
        Api.addMember(owner, otherViewer, "CLIENT", Api.createClient(owner, "Another client"));
        Account stranger = Api.signup("Max");
        Map<String, Object> body = Map.of("name", "Copy", "code", "PERM-2", "startDate", "2026-06-10");
        String path = "/api/projects/" + source.projectId() + "/duplicate";

        // A member plans but does not set projects up. A client viewer of this client sees the project and nothing more.
        designer.as().body(body).post(path).then().statusCode(403);
        designer.as().get(path).then().statusCode(403);
        viewer.as().body(body).post(path).then().statusCode(403);
        viewer.as().get(path).then().statusCode(403);
        // Whoever cannot see the source is not told that it exists.
        otherViewer.as().body(body).post(path).then().statusCode(404);
        stranger.as().body(body).post(path).then().statusCode(404);
        stranger.as().get(path).then().statusCode(404);
        given().contentType("application/json").body(body).post(path).then().statusCode(401);
        owner.as().get("/api/orgs/" + owner.orgId() + "/projects").then().body("$", hasSize(1));

        // A manager may. The copy lands in the source's organisation, never in the manager's own.
        long copy = duplicate(manager, source.projectId(), body);
        owner.as().get("/api/projects/" + copy).then().statusCode(200).body("organisationId", equalTo((int) owner.orgId()));
        manager.as().get("/api/orgs/" + manager.orgId() + "/projects").then().body("$", hasSize(0));
    }

    @Test
    void theCopyHasTheScheduleIssuesOfTheSourceAndNoOthers() {
        Account owner = Api.signup("Nan");
        Account designer = Api.signup("Oz");
        Website source = website(owner, designer, "ISSUE");
        // Make the plan contradict itself in every way the check knows:
        // Build starts while Design is running, a subtask sticks out of Build, Someday (no dates) is
        // waited for, and the project ends before the launch.
        owner.as().body(Map.of("startDate", "2026-03-05", "dueDate", "2026-03-13")).patch("/api/tasks/" + source.build()).then().statusCode(200);
        owner.as().body(Map.of("startDate", "2026-03-04")).patch("/api/tasks/" + source.endpoints()).then().statusCode(200);
        owner.as().body(Map.of("predecessorId", source.someday())).post("/api/tasks/" + source.screens() + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("name", "Website", "code", "ISSUE", "status", "ACTIVE", "startDate", "2026-03-02", "endDate", "2026-03-17"))
                .put("/api/projects/" + source.projectId()).then().statusCode(200);
        Set<String> expected = issues(owner, source.projectId());
        assertThat(expected).containsExactlyInAnyOrder(
                "STARTS_TOO_EARLY Build Design 2", "OUTSIDE_PARENT Endpoints Build 1",
                "WAITS_FOR_UNSCHEDULED Screens Someday null", "AFTER_PROJECT_END Launch null 1");

        // Wherever the copy starts, weekend or not, it says the same.
        int n = 0;
        for (String start : List.of("2026-06-10", "2026-06-12", "2026-06-13", "2026-01-01", "2027-02-23")) {
            long copy = duplicate(owner, source.projectId(), Map.of("name", "Copy " + start, "code", "ISSUE-C" + n++, "startDate", start));
            assertThat(issues(owner, copy)).as("copy starting %s", start).isEqualTo(expected);
        }

        // A finished task is not reported, so the source goes quiet about Build. The copy starts every
        // task again, and what the dates say about an open task is said again.
        owner.as().body(Map.of("status", "DONE")).patch("/api/tasks/" + source.build()).then().statusCode(200);
        assertThat(issues(owner, source.projectId())).doesNotContain("STARTS_TOO_EARLY Build Design 2");
        long copy = duplicate(owner, source.projectId(), Map.of("name", "After", "code", "ISSUE-D", "startDate", "2026-06-10"));
        assertThat(issues(owner, copy)).isEqualTo(expected);
    }

    /** The issues of a project as "type task related days", by title, so that two projects can be compared. */
    private static Set<String> issues(Account who, long projectId) {
        JsonPath tasks = tasks(who, projectId);
        Map<Long, String> titles = new HashMap<>();
        for (Map<String, Object> task : tasks.<Map<String, Object>>getList("$")) {
            titles.put(((Number) task.get("id")).longValue(), (String) task.get("title"));
        }
        Set<String> lines = new TreeSet<>();
        for (Map<String, Object> issue : who.as().get("/api/projects/" + projectId + "/schedule-issues").then().statusCode(200)
                .extract().jsonPath().<Map<String, Object>>getList("$")) {
            Number related = (Number) issue.get("relatedTaskId");
            lines.add(issue.get("type") + " " + titles.get(((Number) issue.get("taskId")).longValue()) + " "
                    + (related == null ? null : titles.get(related.longValue())) + " " + issue.get("days"));
        }
        return lines;
    }

    @Test
    void aFailureLeavesNothingOfTheCopyBehind() {
        Account owner = Api.signup("Pam");
        Account designer = Api.signup("Quy");
        Website source = website(owner, designer, "ATOM");
        long tasksBefore = count("select count(*) from tasks");
        long phasesBefore = count("select count(*) from phases");
        long linksBefore = count("select count(*) from task_dependencies");
        long activityBefore = count("select count(*) from activity_log");

        // The links are written last. For the length of this test the database refuses a lag of
        // two days for new rows, which is what the launch has. By then the project, its phases
        // and its tasks have been inserted.
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "alter table task_dependencies add constraint ck_test_no_lag_of_two check (lag_days <> 2) nocheck").executeUpdate());
        try {
            owner.as().body(Map.of("name", "Half a copy", "code", "ATOM-2", "startDate", "2026-06-10"))
                    .post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(500);
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                    "alter table task_dependencies drop constraint ck_test_no_lag_of_two").executeUpdate());
        }

        owner.as().get("/api/orgs/" + owner.orgId() + "/projects").then().body("project.code", equalTo(List.of("ATOM")));
        assertThat(count("select count(*) from tasks")).isEqualTo(tasksBefore);
        assertThat(count("select count(*) from phases")).isEqualTo(phasesBefore);
        assertThat(count("select count(*) from task_dependencies")).isEqualTo(linksBefore);
        assertThat(count("select count(*) from activity_log")).isEqualTo(activityBefore);
        // With the constraint gone the same request goes through, code included.
        duplicate(owner, source.projectId(), Map.of("name", "A whole copy", "code", "ATOM-2", "startDate", "2026-06-10"));
    }

    private long count(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    @Test
    void aPlanOfThousandsOfTasksTakesNoMoreStatementsThanASmallOne() {
        Account owner = Api.signup("Rex");
        long small = bigProject(owner, "SMALL", 20);
        long large = bigProject(owner, "LARGE", 3000);

        long smallStatements = statementsFor(() -> duplicate(owner, small, Map.of("name", "Small copy", "code", "SMALL-2",
                "startDate", "2026-06-10", "keepAssignees", true)));
        long started = System.nanoTime();
        long largeStatements = statementsFor(() -> duplicate(owner, large, Map.of("name", "Large copy", "code", "LARGE-2",
                "startDate", "2026-06-10", "keepAssignees", true)));
        long millis = (System.nanoTime() - started) / 1_000_000;

        // A hundred and fifty times the tasks, the same number of statements, and few of them.
        assertThat(largeStatements).as("statements for 3000 tasks, %d ms", millis).isEqualTo(smallStatements).isLessThanOrEqualTo(25);

        // And it is all there: parents, subtasks under the right parent, every link, dates moved.
        long copyId = QuarkusTransaction.requiringNew().call(() -> Project.<Project>find("code", "LARGE-2").firstResult().id);
        QuarkusTransaction.requiringNew().run(() -> {
            List<Task> source = Task.forProject(large);
            List<Task> copy = Task.forProject(copyId);
            assertThat(copy).hasSize(3000);
            assertThat(copy).allMatch(t -> t.assigneeId != null && t.status == com.projectplanner.task.TaskStatus.TODO);
            Map<String, Task> byTitle = new HashMap<>();
            copy.forEach(t -> byTitle.put(t.title, t));
            assertThat(byTitle).hasSize(3000);
            for (Task original : source) {
                Task twin = byTitle.get(original.title);
                if (original.parentId != null) {
                    Task parent = source.stream().filter(t -> t.id.equals(original.parentId)).findFirst().orElseThrow();
                    assertThat(twin.parentId).isEqualTo(byTitle.get(parent.title).id);
                } else {
                    assertThat(twin.parentId).isNull();
                }
                assertThat(twin.estimateHours).isEqualByComparingTo(original.estimateHours);
                // The same working day of the plan, counted from its first day, and the same length.
                assertThat(CAL.workingDaysBetween(LocalDate.of(2026, 6, 10), twin.startDate))
                        .isEqualTo(CAL.workingDaysBetween(LocalDate.of(2026, 3, 2), original.startDate));
                assertThat(CAL.duration(twin.startDate, twin.dueDate)).isEqualTo(CAL.duration(original.startDate, original.dueDate));
            }
            assertThat(TaskDependency.forProject(copyId)).hasSize(TaskDependency.forProject(large).size()).hasSize(2499);
        });
        // The chain of links held in the source and holds in the copy.
        owner.as().get("/api/projects/" + copyId + "/schedule-issues").then().statusCode(200).body("$", hasSize(0));
    }

    /**
     * A project with {@code size} tasks, written straight into the database: one in six is a
     * parent with five subtasks, and the parents wait for each other in a chain.
     */
    private long bigProject(Account owner, String code, int size) {
        long projectId = Api.createProject(owner, code);
        QuarkusTransaction.requiringNew().run(() -> {
            Instant now = Instant.now();
            LocalDate start = LocalDate.of(2026, 3, 2);
            List<Task> parents = new ArrayList<>();
            Task parent = null;
            for (int i = 0; i < size; i++) {
                Task task = new Task();
                task.projectId = projectId;
                task.title = code + " task " + i;
                task.description = "Step " + i;
                task.estimateHours = java.math.BigDecimal.valueOf(i % 9 + 1);
                task.assigneeId = owner.userId();
                task.position = i;
                task.createdAt = now;
                task.updatedAt = now;
                // Every parent takes a week, Monday to Friday, one week after the one before.
                int week = i / 6;
                task.startDate = start.plusWeeks(week);
                task.dueDate = task.startDate.plusDays(4);
                if (i % 6 == 0) {
                    parent = task;
                    parents.add(task);
                } else {
                    task.parentId = parent.id;
                    task.startDate = task.startDate.plusDays(i % 6 - 1);
                    task.dueDate = task.startDate;
                }
                task.persist();
            }
            for (int i = 1; i < parents.size(); i++) {
                link(parents.get(i - 1).id, parents.get(i).id, now);
            }
            // And links between subtasks, so that the copy needs their new ids as well.
            List<Task> all = Task.forProject(projectId);
            List<Task> subtasks = all.stream().filter(t -> t.parentId != null).toList();
            for (int i = 1; i < subtasks.size(); i++) {
                if (subtasks.get(i).parentId.equals(subtasks.get(i - 1).parentId)) {
                    link(subtasks.get(i - 1).id, subtasks.get(i).id, now);
                }
            }
        });
        return projectId;
    }

    private static void link(long predecessor, long successor, Instant now) {
        TaskDependency dependency = new TaskDependency();
        dependency.predecessorId = predecessor;
        dependency.successorId = successor;
        dependency.createdAt = now;
        dependency.persist();
    }

    /** How many statements the database ran for {@code request}, by its own count. */
    private long statementsFor(Runnable request) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("set query_statistics true").executeUpdate());
        try {
            long before = executed();
            request.run();
            return executed() - before;
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("set query_statistics false").executeUpdate());
        }
    }

    private long executed() {
        return count("select coalesce(sum(execution_count), 0) from information_schema.query_statistics");
    }
}
