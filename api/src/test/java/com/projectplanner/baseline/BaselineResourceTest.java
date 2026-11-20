package com.projectplanner.baseline;

import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import com.projectplanner.support.BigPlan;
import com.projectplanner.support.LockLog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BaselineResourceTest {

    // 2026-03-02 is a Monday.

    @Inject
    EntityManager em;

    /** Design, then Build with a subtask, then the launch. Copy runs next to Build, and Someday has no dates. */
    private record Plan(long projectId, long clientId, long phase, long design, long build, long endpoints, long launch, long copy, long someday) {
    }

    private static Plan plan(Account owner, String code) {
        long clientId = Api.createClient(owner, "Client of " + code);
        Number id = owner.as().body(Map.of("name", "Website", "code", code, "clientId", clientId, "status", "ACTIVE",
                        "startDate", "2026-03-02", "endDate", "2026-03-20"))
                .post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        long projectId = id.longValue();
        Number phase = owner.as().body(Map.of("name", "Build phase", "startDate", "2026-03-09", "endDate", "2026-03-13")).post("/api/projects/" + projectId + "/phases")
                .then().statusCode(201).extract().path("id");
        long design = Api.createTask(owner, projectId, Map.of("title", "Design", "estimateHours", 20, "startDate", "2026-03-02", "dueDate", "2026-03-06"));
        long build = Api.createTask(owner, projectId, Map.of("title", "Build", "phaseId", phase, "estimateHours", 40,
                "startDate", "2026-03-09", "dueDate", "2026-03-13"));
        long endpoints = Api.createTask(owner, projectId, Map.of("title", "Endpoints", "parentId", build, "estimateHours", 16,
                "startDate", "2026-03-09", "dueDate", "2026-03-10"));
        long launch = Api.createTask(owner, projectId, Map.of("title", "Launch", "milestone", true, "dueDate", "2026-03-18"));
        long copy = Api.createTask(owner, projectId, Map.of("title", "Copy", "estimateHours", 10, "startDate", "2026-03-09", "dueDate", "2026-03-13"));
        long someday = Api.createTask(owner, projectId, Map.of("title", "Someday", "estimateHours", 4));
        owner.as().body(Map.of("predecessorId", design)).post("/api/tasks/" + build + "/dependencies").then().statusCode(201);
        owner.as().body(Map.of("predecessorId", build)).post("/api/tasks/" + launch + "/dependencies").then().statusCode(201);
        return new Plan(projectId, clientId, phase.longValue(), design, build, endpoints, launch, copy, someday);
    }

    private static long take(Account who, long projectId, String name) {
        Number id = who.as().body(Map.of("name", name)).post("/api/projects/" + projectId + "/baselines")
                .then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static JsonPath comparison(Account who, long baselineId) {
        return who.as().get("/api/baselines/" + baselineId + "/comparison").then().statusCode(200).extract().jsonPath();
    }

    private static String task(String title) {
        return "tasks.find { it.title == '" + title + "' }";
    }

    /** "change start..due -> start..due (start slip/finish slip)" for one task of a comparison. */
    private static String line(JsonPath comparison, String title) {
        String t = task(title);
        return comparison.getString(t + ".change") + " " + comparison.getString(t + ".baselineStart") + ".." + comparison.getString(t + ".baselineDue")
                + " -> " + comparison.getString(t + ".currentStart") + ".." + comparison.getString(t + ".currentDue")
                + " (" + comparison.getString(t + ".startSlipDays") + "/" + comparison.getString(t + ".finishSlipDays") + ")";
    }

    @Test
    void savesThePlanAsItIsAndComparesItWithWhatItBecomes() {
        Account owner = Api.signup("Ada");
        Plan plan = plan(owner, "BASE");

        owner.as().body(Map.of("name", " Signed off 3 March ")).post("/api/projects/" + plan.projectId() + "/baselines")
                .then().statusCode(201)
                .body("name", equalTo("Signed off 3 March"))
                .body("reference", equalTo(true))
                .body("tasks", equalTo(6))
                .body("projectStart", equalTo("2026-03-02"))
                .body("projectEnd", equalTo("2026-03-20"))
                .body("createdByName", equalTo("Ada"))
                .body("createdAt", notNullValue());
        long baseline = owner.as().get("/api/projects/" + plan.projectId() + "/baselines").then().statusCode(200)
                .body("$", hasSize(1)).extract().jsonPath().getLong("[0].id");

        // Nothing has happened yet.
        JsonPath same = comparison(owner, baseline);
        assertThat(same.getList("tasks.change", String.class)).hasSize(6).containsOnly("UNCHANGED");
        assertThat(same.getInt("counts.unchanged")).isEqualTo(6);
        assertThat(same.getString("planEnd.change")).isEqualTo("UNCHANGED");
        assertThat(same.getString("planEnd.baseline")).isEqualTo("2026-03-18");
        assertThat(same.getInt("planEnd.slipDays")).isZero();
        assertThat(same.getFloat("baselineEstimateHours")).isEqualTo(50f).isEqualTo(same.getFloat("currentEstimateHours"));
        assertThat(same.getString("baseline.name")).isEqualTo("Signed off 3 March");

        // Then the plan moves, in every way it can.
        // Design slips two working days and pushes Build, which is then made two days longer and pushes the launch.
        owner.as().body(Map.of("days", 2)).post("/api/tasks/" + plan.design() + "/shift").then().statusCode(200);
        owner.as().body(Map.of("dueDate", "2026-03-19")).patch("/api/tasks/" + plan.build()).then().statusCode(200);
        // Copy is pulled in, Endpoints is dropped, Someday gets dates, and there is a new task.
        owner.as().body(Map.of("startDate", "2026-03-05", "dueDate", "2026-03-11")).patch("/api/tasks/" + plan.copy()).then().statusCode(200);
        owner.as().delete("/api/tasks/" + plan.endpoints()).then().statusCode(204);
        owner.as().body(Map.of("startDate", "2026-03-23", "dueDate", "2026-03-24")).patch("/api/tasks/" + plan.someday()).then().statusCode(200);
        Api.createTask(owner, plan.projectId(), Map.of("title", "Extra", "estimateHours", 6, "startDate", "2026-03-16", "dueDate", "2026-03-17"));
        owner.as().body(Map.of("title", "Design and research")).patch("/api/tasks/" + plan.design()).then().statusCode(200);
        // The phase ends four working days later, and is renamed.
        owner.as().body(Map.of("name", "Build and test", "startDate", "2026-03-09", "endDate", "2026-03-19"))
                .put("/api/phases/" + plan.phase()).then().statusCode(200);
        owner.as().body(Map.of("name", "Website", "code", "BASE", "status", "ACTIVE", "startDate", "2026-03-02", "endDate", "2026-03-27"))
                .put("/api/projects/" + plan.projectId()).then().statusCode(200);

        JsonPath now = comparison(owner, baseline);
        assertThat(line(now, "Design and research")).isEqualTo("SLIPPED 2026-03-02..2026-03-06 -> 2026-03-04..2026-03-10 (2/2)");
        assertThat(line(now, "Build")).isEqualTo("SLIPPED 2026-03-09..2026-03-13 -> 2026-03-11..2026-03-19 (2/4)");
        assertThat(line(now, "Launch")).isEqualTo("SLIPPED 2026-03-18..2026-03-18 -> 2026-03-20..2026-03-20 (2/2)");
        assertThat(line(now, "Copy")).isEqualTo("PULLED_IN 2026-03-09..2026-03-13 -> 2026-03-05..2026-03-11 (-2/-2)");
        assertThat(line(now, "Endpoints")).isEqualTo("REMOVED 2026-03-09..2026-03-10 -> null..null (null/null)");
        assertThat(line(now, "Someday")).isEqualTo("ADDED null..null -> 2026-03-23..2026-03-24 (null/null)");
        assertThat(line(now, "Extra")).isEqualTo("ADDED null..null -> 2026-03-16..2026-03-17 (null/null)");
        // Gone, given dates since, and new: three different things behind two words.
        assertThat(now.getBoolean(task("Endpoints") + ".existsNow")).isFalse();
        assertThat(now.getBoolean(task("Someday") + ".existedThen")).isTrue();
        assertThat(now.getBoolean(task("Extra") + ".existedThen")).isFalse();
        assertThat(now.getBoolean(task("Launch") + ".milestone")).isTrue();
        assertThat(now.getString(task("Build") + ".phaseName")).isEqualTo("Build and test");
        assertThat(now.getString(task("Endpoints") + ".phaseName")).isEqualTo("Build phase");
        // The phase is compared like a task, under the name it has now.
        assertThat(now.getList("phases")).hasSize(1);
        assertThat(now.getString("phases[0].name")).isEqualTo("Build and test");
        assertThat(now.getString("phases[0].change")).isEqualTo("SLIPPED");
        assertThat(now.getString("phases[0].baselineEnd")).isEqualTo("2026-03-13");
        assertThat(now.getString("phases[0].currentEnd")).isEqualTo("2026-03-19");
        assertThat(now.getInt("phases[0].finishSlipDays")).isEqualTo(4);
        assertThat(now.getInt("phases[0].startSlipDays")).isZero();

        assertThat(now.getMap("counts")).isEqualTo(Map.of("unchanged", 0, "slipped", 3, "pulledIn", 1, "added", 2, "removed", 1));
        // The project's own end was moved a week. The plan now ends on Someday's due date, four working days later than it did.
        assertThat(now.getString("projectEnd.change")).isEqualTo("SLIPPED");
        assertThat(now.getInt("projectEnd.slipDays")).isEqualTo(5);
        assertThat(now.getString("planEnd.baseline")).isEqualTo("2026-03-18");
        assertThat(now.getString("planEnd.current")).isEqualTo("2026-03-24");
        assertThat(now.getInt("planEnd.slipDays")).isEqualTo(4);
        // Then: Design 20, Endpoints 16 (not Build's own 40 next to it), Copy 10, Someday 4.
        // Now: Build has no subtask left and counts itself, and Extra is in: 20 + 40 + 10 + 4 + 6.
        assertThat(now.getFloat("baselineEstimateHours")).isEqualTo(50f);
        assertThat(now.getFloat("currentEstimateHours")).isEqualTo(80f);

        // The baseline itself is what it was: six tasks, the old project end, the old name of the task in its rows.
        owner.as().get("/api/projects/" + plan.projectId() + "/baselines").then()
                .body("[0].tasks", equalTo(6)).body("[0].projectEnd", equalTo("2026-03-20"));
        List<String> titles = QuarkusTransaction.requiringNew().call(() -> BaselineTask.<BaselineTask>list("baselineId", baseline)
                .stream().map(t -> t.title).sorted().toList());
        assertThat(titles).containsExactly("Build", "Copy", "Design", "Endpoints", "Launch", "Someday");
    }

    @Test
    void theScheduleCheckLooksAtThePlanAndNotAtABaseline() {
        Account owner = Api.signup("Bo");
        Plan plan = plan(owner, "LIVE");
        long baseline = take(owner, plan.projectId(), "Agreed");
        owner.as().get("/api/projects/" + plan.projectId() + "/schedule-issues").then().body("$", hasSize(0));

        // Build is pulled in front of Design. The baseline has it where it should be.
        owner.as().body(Map.of("startDate", "2026-03-05", "dueDate", "2026-03-11")).patch("/api/tasks/" + plan.build()).then().statusCode(200);
        owner.as().get("/api/projects/" + plan.projectId() + "/schedule-issues").then()
                .body("type", hasItem("STARTS_TOO_EARLY"))
                .body("find { it.type == 'STARTS_TOO_EARLY' }.suggestedStart", equalTo("2026-03-09"));
        // "Move to" puts Build back where the baseline has it, and the comparison agrees.
        owner.as().body(Map.of("startDate", "2026-03-09", "dueDate", "2026-03-13")).patch("/api/tasks/" + plan.build()).then().statusCode(200);
        assertThat(line(comparison(owner, baseline), "Build")).isEqualTo("UNCHANGED 2026-03-09..2026-03-13 -> 2026-03-09..2026-03-13 (0/0)");

        // Taking another baseline changes nothing about the plan.
        JsonPath before = owner.as().get("/api/projects/" + plan.projectId() + "/tasks").then().extract().jsonPath();
        take(owner, plan.projectId(), "Second look");
        JsonPath after = owner.as().get("/api/projects/" + plan.projectId() + "/tasks").then().extract().jsonPath();
        assertThat(after.getList("updatedAt")).isEqualTo(before.getList("updatedAt"));
        owner.as().get("/api/projects/" + plan.projectId() + "/critical-path").then().body("finish", equalTo("2026-03-18"));
    }

    @Test
    void keepsSeveralBaselinesAndOneOfThemAsTheReference() {
        Account owner = Api.signup("Cy");
        Plan plan = plan(owner, "MANY");
        String path = "/api/projects/" + plan.projectId() + "/baselines";
        long first = take(owner, plan.projectId(), "Signed off");
        long second = take(owner, plan.projectId(), "After the change request");
        owner.as().get(path).then()
                .body("name", equalTo(List.of("Signed off", "After the change request")))
                .body("reference", equalTo(List.of(true, false)));

        // Names tell baselines apart, so two cannot share one. Blank is no name.
        owner.as().body(Map.of("name", "signed OFF")).post(path).then().statusCode(409).body("code", equalTo("baseline_name_taken"));
        owner.as().body(Map.of("name", "  ")).post(path).then().statusCode(400).body("errors.field", hasItem("name"));
        owner.as().body(Map.of("name", "Signed off")).patch("/api/baselines/" + second).then().statusCode(409);
        owner.as().body(Map.of("name", "   ")).patch("/api/baselines/" + second).then().statusCode(400).body("errors.field", hasItem("name"));
        owner.as().get(path).then().body("name", equalTo(List.of("Signed off", "After the change request")));

        // Rename one, make the other the reference: there is always exactly one.
        owner.as().body(Map.of("name", "Signed off 3 March")).patch("/api/baselines/" + first).then().statusCode(200)
                .body("name", equalTo("Signed off 3 March")).body("reference", equalTo(true));
        owner.as().body(Map.of("reference", true)).patch("/api/baselines/" + second).then().statusCode(200).body("reference", equalTo(true));
        owner.as().get(path).then().body("reference", equalTo(List.of(false, true)));
        // A new one can take the mark with it.
        Number third = owner.as().body(Map.of("name", "Third", "reference", true)).post(path).then().statusCode(201)
                .body("reference", equalTo(true)).extract().path("id");
        owner.as().get(path).then().body("reference", equalTo(List.of(false, false, true)));

        // When the reference is deleted the latest one that is left takes over.
        owner.as().delete("/api/baselines/" + third).then().statusCode(204);
        owner.as().get(path).then().body("id", equalTo(List.of((int) first, (int) second))).body("reference", equalTo(List.of(false, true)));
        owner.as().get("/api/baselines/" + third + "/comparison").then().statusCode(404);
        owner.as().delete("/api/baselines/" + third).then().statusCode(404);

        // Ten is the limit.
        for (int i = 3; i <= BaselineService.MAX_PER_PROJECT; i++) {
            take(owner, plan.projectId(), "Baseline " + i);
        }
        owner.as().body(Map.of("name", "One too many")).post(path).then().statusCode(409).body("code", equalTo("baseline_limit"));
        owner.as().delete("/api/baselines/" + first).then().statusCode(204);
        take(owner, plan.projectId(), "Room again");
        owner.as().get(path).then().body("$", hasSize(BaselineService.MAX_PER_PROJECT)).body("findAll { it.reference }", hasSize(1));
    }

    @Test
    void everybodyWhoSeesTheProjectSeesTheComparisonAndOnlyManagersChangeBaselines() {
        Account owner = Api.signup("Dee");
        Plan plan = plan(owner, "WHO");
        Account member = Api.signup("Eli");
        Api.addMember(owner, member, "MEMBER");
        Account viewer = Api.signup("Fay");
        Api.addMember(owner, viewer, "CLIENT", plan.clientId());
        Account otherViewer = Api.signup("Gus");
        Api.addMember(owner, otherViewer, "CLIENT", Api.createClient(owner, "Another client"));
        Account stranger = Api.signup("Hal");
        long baseline = take(owner, plan.projectId(), "Agreed");
        String list = "/api/projects/" + plan.projectId() + "/baselines";
        String one = "/api/baselines/" + baseline;

        // The team and the client of this project read: they see these dates on the timeline already.
        for (Account reader : List.of(member, viewer)) {
            reader.as().get(list).then().statusCode(200).body("$", hasSize(1));
            reader.as().get(one + "/comparison").then().statusCode(200).body("tasks", hasSize(6));
            reader.as().body(Map.of("name", "Mine")).post(list).then().statusCode(403);
            reader.as().body(Map.of("name", "Renamed")).patch(one).then().statusCode(403);
            reader.as().delete(one).then().statusCode(403);
        }
        // Whoever cannot see the project learns nothing, not even that the baseline exists.
        for (Account outsider : List.of(otherViewer, stranger)) {
            outsider.as().get(list).then().statusCode(404);
            outsider.as().get(one + "/comparison").then().statusCode(404);
            outsider.as().body(Map.of("name", "Renamed")).patch(one).then().statusCode(404);
            outsider.as().delete(one).then().statusCode(404);
            outsider.as().body(Map.of("name", "Mine")).post(list).then().statusCode(404);
        }
        given().get(one + "/comparison").then().statusCode(401);
        owner.as().get(list).then().body("name", equalTo(List.of("Agreed")));

        // A manager may.
        Account manager = Api.signup("Ina");
        Api.addMember(owner, manager, "MANAGER");
        manager.as().body(Map.of("name", "By the manager")).post(list).then().statusCode(201).body("createdByName", equalTo("Ina"));
    }

    @Test
    void baselinesStayWithTheirProject() {
        Account owner = Api.signup("Jo");
        Plan plan = plan(owner, "STAY");
        long baseline = take(owner, plan.projectId(), "Agreed");

        // A copy of the project is a new plan. It has no history to compare with.
        Number copy = owner.as().body(Map.of("name", "Again", "code", "STAY-2", "startDate", "2026-06-10"))
                .post("/api/projects/" + plan.projectId() + "/duplicate").then().statusCode(201).extract().path("id");
        owner.as().get("/api/projects/" + copy + "/baselines").then().statusCode(200).body("$", hasSize(0));
        // The source's baseline does not see the copy's tasks as added.
        assertThat(comparison(owner, baseline).getList("tasks.change", String.class)).hasSize(6).containsOnly("UNCHANGED");

        // Deleting the project takes its baselines along.
        owner.as().delete("/api/projects/" + plan.projectId()).then().statusCode(204);
        owner.as().get("/api/baselines/" + baseline + "/comparison").then().statusCode(404);
        long rows = QuarkusTransaction.requiringNew().call(() -> BaselineTask.count("baselineId", baseline));
        assertThat(rows).isZero();
    }

    @Test
    void aBaselineOfThousandsOfTasksTakesNoMoreStatementsThanASmallOne() {
        Account owner = Api.signup("Kim");
        long small = BigPlan.create(owner, "BSMALL", 20);
        long large = BigPlan.create(owner, "BLARGE", 3000);

        long smallStatements = BigPlan.statementsFor(em, () -> take(owner, small, "Agreed"));
        Map<String, Long> ids = new HashMap<>();
        long started = System.nanoTime();
        long largeStatements = BigPlan.statementsFor(em, () -> ids.put("large", take(owner, large, "Agreed")));
        long millis = (System.nanoTime() - started) / 1_000_000;
        // One of them is the session check that CurrentUser.id() makes once per request.
        assertThat(largeStatements).as("statements for 3000 tasks, %d ms", millis).isEqualTo(smallStatements).isLessThanOrEqualTo(18);

        owner.as().get("/api/projects/" + large + "/baselines").then().body("[0].tasks", equalTo(3000));
        // One task in the middle of the chain slips a week and takes everything after it along.
        Number middle = owner.as().get("/api/projects/" + large + "/tasks").then().extract()
                .path("find { it.title == 'BLARGE task 1500' }.id");
        owner.as().body(Map.of("days", 5)).post("/api/tasks/" + middle + "/shift").then().statusCode(200);
        owner.as().get("/api/baselines/" + ids.get("large") + "/comparison").then().statusCode(200)
                .body("tasks", hasSize(3000))
                .body("counts.slipped", equalTo(250))
                .body("counts.unchanged", equalTo(2750))
                .body("tasks.findAll { it.change == 'SLIPPED' }.finishSlipDays", everyItem(equalTo(5)))
                .body("planEnd.slipDays", equalTo(5))
                .body("projectEnd.change", equalTo("UNCHANGED"))
                .body("projectEnd.slipDays", nullValue());
    }

    /** Sends the requests at the same moment, each from its own thread, and gives their status codes in the order given. */
    private static List<Integer> together(List<Callable<Response>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> answers = new ArrayList<>();
            for (Callable<Response> request : requests) {
                answers.add(executor.submit(() -> {
                    go.await();
                    return request.call();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Response> answer : answers) {
                codes.add(answer.get(30, TimeUnit.SECONDS).statusCode());
            }
            return codes;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void changesThatArriveTogetherKeepTheLimitTheNamesAndTheOneReference() throws Exception {
        Account owner = Api.signup("Lou");
        Plan plan = plan(owner, "RACE");
        String path = "/api/projects/" + plan.projectId() + "/baselines";
        for (int i = 1; i < BaselineService.MAX_PER_PROJECT; i++) {
            take(owner, plan.projectId(), "Baseline " + i);
        }

        // One slot is left and four requests want it.
        List<Callable<Response>> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> body = Map.of("name", "Last " + i, "reference", true);
            four.add(() -> owner.as().body(body).post(path));
        }
        assertThat(together(four)).containsExactlyInAnyOrder(201, 409, 409, 409);
        owner.as().get(path).then().body("$", hasSize(BaselineService.MAX_PER_PROJECT)).body("findAll { it.reference }", hasSize(1));

        // Four renames to one name, and the mark asked for on four baselines at once.
        List<Number> ids = owner.as().get(path).then().extract().path("id");
        List<Callable<Response>> renames = new ArrayList<>();
        List<Callable<Response>> marks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String one = "/api/baselines/" + ids.get(i);
            renames.add(() -> owner.as().body(Map.of("name", "Agreed")).patch(one));
            marks.add(() -> owner.as().body(Map.of("reference", true)).patch(one));
        }
        assertThat(together(renames)).containsExactlyInAnyOrder(200, 409, 409, 409);
        assertThat(together(marks)).containsOnly(200);
        owner.as().get(path).then().body("findAll { it.name == 'Agreed' }", hasSize(1)).body("findAll { it.reference }", hasSize(1));

        // Deleting the reference twice at once: one delete, one 404, and the latest left takes the mark once.
        Number reference = owner.as().get(path).then().extract().path("find { it.reference }.id");
        List<Callable<Response>> deletes = List.of(() -> owner.as().delete("/api/baselines/" + reference),
                () -> owner.as().delete("/api/baselines/" + reference));
        assertThat(together(deletes)).containsExactlyInAnyOrder(204, 404);
        owner.as().get(path).then().body("$", hasSize(BaselineService.MAX_PER_PROJECT - 1)).body("findAll { it.reference }", hasSize(1));
    }

    @Test
    void everyChangeToTheBaselinesLocksTheProjectAndNothingBeforeIt() {
        Account owner = Api.signup("Max");
        Plan plan = plan(owner, "LOCK");
        String path = "/api/projects/" + plan.projectId() + "/baselines";
        long first = take(owner, plan.projectId(), "First");

        LockLog.start();
        Number second = owner.as().body(Map.of("name", "Second")).post(path).then().statusCode(201).extract().path("id");
        assertThat(LockLog.stop()).containsExactly("projects");
        LockLog.start();
        owner.as().body(Map.of("name", "Renamed")).patch("/api/baselines/" + first).then().statusCode(200);
        assertThat(LockLog.stop()).containsExactly("projects");
        LockLog.start();
        owner.as().body(Map.of("reference", true)).patch("/api/baselines/" + second).then().statusCode(200);
        assertThat(LockLog.stop()).containsExactly("projects");
        LockLog.start();
        owner.as().delete("/api/baselines/" + second).then().statusCode(204);
        assertThat(LockLog.stop()).containsExactly("projects");
        // Reading locks nothing.
        LockLog.start();
        owner.as().get(path).then().statusCode(200);
        owner.as().get("/api/baselines/" + first + "/comparison").then().statusCode(200);
        assertThat(LockLog.stop()).isEmpty();
    }
}
