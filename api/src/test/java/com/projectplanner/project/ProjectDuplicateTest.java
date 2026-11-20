package com.projectplanner.project;

import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.BigPlan;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.TestClock;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskDependency;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
                .body("[0].summary", equalTo("Copied the plan from another project: 3 phases, 6 tasks and 2 links"))
                .body("[0].relatedProjectId", equalTo((int) source.projectId()))
                .body("[0].relatedProjectName", equalTo("Website"));
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
                .body("[0].summary", equalTo("Copied the plan from another project: 0 phases, 0 tasks and 0 links"));

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
    void proposesTheNextWorkingDayOfTheOrganisationAsTheStart() {
        Account owner = Api.signup("Val");
        long projectId = Api.createProject(owner, "TODAY");
        owner.as().body(Map.of("timezone", "Pacific/Auckland")).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        try {
            // Friday evening in UTC is Saturday morning in Auckland, so the organisation's next working day is Monday.
            TestClock.fix(Instant.parse("2026-06-12T20:00:00Z"));
            owner.as().get("/api/projects/" + projectId + "/duplicate").then().statusCode(200).body("startDate", equalTo("2026-06-15"));
            // Tuesday noon in UTC is Wednesday there, a working day: that day itself.
            TestClock.fix(Instant.parse("2026-06-16T12:30:00Z"));
            owner.as().get("/api/projects/" + projectId + "/duplicate").then().statusCode(200).body("startDate", equalTo("2026-06-17"));
        } finally {
            TestClock.reset();
        }
    }

    @Test
    void theHistoryOfACopyDoesNotNameASourceThatItsReaderMayNotSee() {
        Account owner = Api.signup("Wim");
        Account designer = Api.signup("Xan");
        Website source = website(owner, designer, "LEAK");
        long globex = Api.createClient(owner, "Globex");
        Account globexViewer = Api.signup("Yve");
        Api.addMember(owner, globexViewer, "CLIENT", globex);
        Account sourceViewer = Api.signup("Zoe");
        Api.addMember(owner, sourceViewer, "CLIENT", source.clientId());

        // The plan that was made for one client is copied for another.
        long copy = duplicate(owner, source.projectId(), Map.of("name", "Portal for Globex", "code", "GLX-PORTAL",
                "startDate", "2026-06-10", "clientId", globex));
        String activity = "/api/projects/" + copy + "/activity";

        // Globex sees its own project and its history, and learns nothing about the other client's project.
        globexViewer.as().get("/api/projects/" + source.projectId()).then().statusCode(404);
        String body = globexViewer.as().get(activity).then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].summary", equalTo("Copied the plan from another project: 3 phases, 6 tasks and 2 links"))
                .body("[0].relatedProjectId", nullValue())
                .body("[0].relatedProjectName", nullValue())
                .extract().asString();
        assertThat(body).doesNotContain("Website").doesNotContain("relatedProject").doesNotContain("LEAK");
        // The team may see both, and gets the name and the id to link to.
        for (Account reader : List.of(owner, designer)) {
            reader.as().get(activity).then().statusCode(200)
                    .body("[0].relatedProjectId", equalTo((int) source.projectId()))
                    .body("[0].relatedProjectName", equalTo("Website"));
        }
        owner.as().get("/api/orgs/" + owner.orgId() + "/activity").then()
                .body("find { it.action == 'project.duplicated' }.relatedProjectName", equalTo("Website"));
        // The client of the source does not see the copy, so there is nothing to read there.
        sourceViewer.as().get(activity).then().statusCode(404);

        // A source that is gone is no longer named to anybody.
        owner.as().delete("/api/projects/" + source.projectId()).then().statusCode(204);
        owner.as().get(activity).then().statusCode(200)
                .body("[0].summary", equalTo("Copied the plan from another project: 3 phases, 6 tasks and 2 links"))
                .body("[0].relatedProjectId", nullValue());
    }

    @Test
    void aCodeThatSomebodyElseTakesAtTheSameMomentIsAConflictAndNotAnError() throws Exception {
        Account owner = Api.signup("Abe");
        Account designer = Api.signup("Bip");
        Website source = website(owner, designer, "RACE");
        Map<String, Object> copy = Map.of("name", "A copy", "code", "RACE-2", "startDate", "2026-06-10");
        Map<String, Object> fresh = Map.of("name", "A new project", "code", "RACE-3", "status", "PLANNED");

        // Both ways of making a project: the copy and the plain create.
        whileAnotherRequestHoldsTheCode(owner, "RACE-2",
                () -> owner.as().body(copy).post("/api/projects/" + source.projectId() + "/duplicate"))
                .then().statusCode(409).body("code", equalTo("code_taken")).body("message", equalTo("Another project already uses the code RACE-2"));
        whileAnotherRequestHoldsTheCode(owner, "RACE-3",
                () -> owner.as().body(fresh).post("/api/orgs/" + owner.orgId() + "/projects"))
                .then().statusCode(409).body("code", equalTo("code_taken"));

        // The request that lost left nothing behind: one project per code, and no tasks of half a copy.
        owner.as().get("/api/orgs/" + owner.orgId() + "/projects").then()
                .body("project.code.sort()", equalTo(List.of("RACE", "RACE-2", "RACE-3")));
        long strayTasks = count("select count(*) from tasks t join projects p on p.id = t.project_id where p.code = 'RACE-2'");
        assertThat(strayTasks).isZero();
    }

    /**
     * Runs {@code request} while another transaction has inserted a project with {@code code} and
     * has not committed yet. The request's own check cannot see that row, so it goes on to insert,
     * and waits there for the other transaction. Only then is the other one committed, which leaves
     * the request with the unique constraint and nothing else to stop it.
     */
    private Response whileAnotherRequestHoldsTheCode(Account owner, String code, Callable<Response> request) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        QuarkusTransaction.begin();
        try {
            em.createNativeQuery("insert into projects (organisation_id, name, code, status, color, created_at, updated_at) "
                            + "values (?1, 'Somebody else', ?2, 'PLANNED', '#4f6bed', current_timestamp, current_timestamp)")
                    .setParameter(1, owner.orgId()).setParameter(2, code).executeUpdate();
            Future<Response> answer = executor.submit(request);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            // The request is at its insert when another session is in the middle of one into projects.
            while (((Number) em.createNativeQuery("select count(*) from information_schema.sessions "
                            + "where session_id <> session_id() and lower(executing_statement) like 'insert into projects%'")
                    .getSingleResult()).intValue() == 0) {
                assertThat(answer.isDone()).as("the request answered before it had to wait for the other insert").isFalse();
                assertThat(System.nanoTime()).as("the request never reached its insert").isLessThan(deadline);
                Thread.sleep(10);
            }
            QuarkusTransaction.commit();
            return answer.get(10, TimeUnit.SECONDS);
        } finally {
            if (QuarkusTransaction.isActive()) {
                QuarkusTransaction.rollback();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void aSourceThatChangesWhileItIsCopiedIsAConflictAndNothingIsCopied() {
        Account owner = Api.signup("Cid");
        Account designer = Api.signup("Dru");
        Website source = website(owner, designer, "MOVE");
        Api.createTask(owner, source.projectId(), Map.of("title", VanishingTask.TITLE));
        long tasksBefore = count("select count(*) from tasks");
        long projectsBefore = count("select count(*) from projects");

        // Somebody deletes a task of the source between the moment the copy has read the plan and the
        // moment it writes the tasks. A trigger stands in for that somebody: it fires when the copy's
        // project row goes in, which is after the reading and before the writing.
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("create trigger a_task_vanishes before insert on projects "
                + "for each row call '" + VanishingTask.class.getName() + "'").executeUpdate());
        try {
            owner.as().body(Map.of("name", "A copy", "code", "MOVE-2", "startDate", "2026-06-10"))
                    .post("/api/projects/" + source.projectId() + "/duplicate").then().statusCode(409)
                    .body("code", equalTo("source_changed"))
                    .body("message", equalTo("The project was changed while it was being copied. Nothing was copied, so try again."));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("drop trigger a_task_vanishes").executeUpdate());
        }

        // Nothing of the copy, and the deletion that the trigger made inside the same transaction is undone with it.
        assertThat(count("select count(*) from projects")).isEqualTo(projectsBefore);
        assertThat(count("select count(*) from tasks")).isEqualTo(tasksBefore);
        // Trying again, as the message says, works.
        duplicate(owner, source.projectId(), Map.of("name", "A copy", "code", "MOVE-2", "startDate", "2026-06-10"));
    }

    /**
     * Deletes the task with a title of its own when it fires. Used as a trigger on the test
     * database, which loads the class by name, so it cannot be told anything by the test.
     */
    public static class VanishingTask implements org.h2.api.Trigger {

        static final String TITLE = "A task that vanishes";

        @Override
        public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
            try (java.sql.PreparedStatement statement = connection.prepareStatement("delete from tasks where title = ?")) {
                statement.setString(1, TITLE);
                statement.executeUpdate();
            }
        }
    }

    @Test
    void aPlanThatDoesNotFitIntoOneStatementIsSpreadOverSeveralAndComesOutTheSame() {
        Account owner = Api.signup("Eda");
        Account designer = Api.signup("Fox");
        Website source = website(owner, designer, "SPLIT");
        long whole = duplicate(owner, source.projectId(), Map.of("name", "In one go", "code", "SPLIT-2", "startDate", "2026-06-10", "keepAssignees", true));
        long wholeStatements = BigPlan.statementsFor(em, () -> duplicate(owner, source.projectId(),
                Map.of("name", "Counted", "code", "SPLIT-3", "startDate", "2026-06-10", "keepAssignees", true)));

        // Two rows to a statement: three phases, four tasks, two subtasks and two links need 2 + 2 + 1 + 1 of them instead of 4.
        int usual = ProjectDuplicator.rowsPerStatement;
        ProjectDuplicator.rowsPerStatement = 2;
        long splitStatements;
        Map<String, Long> ids = new HashMap<>();
        try {
            splitStatements = BigPlan.statementsFor(em, () -> ids.put("split", duplicate(owner, source.projectId(),
                    Map.of("name", "In pieces", "code", "SPLIT-4", "startDate", "2026-06-10", "keepAssignees", true))));
        } finally {
            ProjectDuplicator.rowsPerStatement = usual;
        }
        assertThat(usual).isEqualTo(5000);
        assertThat(splitStatements).isEqualTo(wholeStatements + 2);

        assertThat(shape(owner, ids.get("split"))).isEqualTo(shape(owner, whole));
        assertThat(shape(owner, whole)).hasSize(6 + 3 + 2);
    }

    /** Everything a copy consists of, by title instead of id, so that two copies can be compared. */
    private static List<String> shape(Account who, long projectId) {
        JsonPath tasks = tasks(who, projectId);
        JsonPath phases = who.as().get("/api/projects/" + projectId + "/phases").then().extract().jsonPath();
        Map<Long, String> titles = new HashMap<>();
        Map<Long, String> phaseNames = new HashMap<>();
        for (Map<String, Object> task : tasks.<Map<String, Object>>getList("$")) {
            titles.put(((Number) task.get("id")).longValue(), (String) task.get("title"));
        }
        for (Map<String, Object> phase : phases.<Map<String, Object>>getList("$")) {
            phaseNames.put(((Number) phase.get("id")).longValue(), phase.get("position") + ":" + phase.get("name"));
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> task : tasks.<Map<String, Object>>getList("$")) {
            Number parent = (Number) task.get("parentId");
            Number phase = (Number) task.get("phaseId");
            lines.add("task " + task.get("title") + " " + task.get("startDate") + ".." + task.get("dueDate") + " position " + task.get("position")
                    + " under " + (parent == null ? null : titles.get(parent.longValue()))
                    + " in " + (phase == null ? null : phaseNames.get(phase.longValue())) + " for " + task.get("assigneeName"));
        }
        phaseNames.values().forEach(name -> lines.add("phase " + name));
        for (Map<String, Object> link : who.as().get("/api/projects/" + projectId + "/dependencies").then().extract()
                .jsonPath().<Map<String, Object>>getList("$")) {
            lines.add("link " + titles.get(((Number) link.get("predecessorId")).longValue()) + " -> "
                    + titles.get(((Number) link.get("successorId")).longValue()) + " lag " + link.get("lagDays"));
        }
        lines.sort(null);
        return lines;
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
        long small = BigPlan.create(owner, "SMALL", 20);
        long large = BigPlan.create(owner, "LARGE", 3000);

        long smallStatements = BigPlan.statementsFor(em, () -> duplicate(owner, small, Map.of("name", "Small copy", "code", "SMALL-2",
                "startDate", "2026-06-10", "keepAssignees", true)));
        long started = System.nanoTime();
        long largeStatements = BigPlan.statementsFor(em, () -> duplicate(owner, large, Map.of("name", "Large copy", "code", "LARGE-2",
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
}
