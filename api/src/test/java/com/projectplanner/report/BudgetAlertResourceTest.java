package com.projectplanner.report;

import com.projectplanner.project.Project;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BudgetAlertResourceTest {

    /** Monday of last week: a week that is over, so every day of it can take time. */
    private static final LocalDate WEEK = WorkCalendar.weekStart(LocalDate.now(ZoneOffset.UTC)).minusWeeks(1);

    private static long project(Account owner, String code, Number budgetHours, Number budgetAmount) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Project " + code, "code", code, "status", "ACTIVE"));
        if (budgetHours != null) {
            body.put("budgetHours", budgetHours);
        }
        if (budgetAmount != null) {
            body.put("budgetAmount", budgetAmount);
        }
        Number id = owner.as().body(body).post("/api/orgs/" + owner.orgId() + "/projects").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    private static void budget(Account owner, long projectId, String code, Number budgetHours, Number budgetAmount) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Project " + code, "code", code, "status", "ACTIVE"));
        if (budgetHours != null) {
            body.put("budgetHours", budgetHours);
        }
        if (budgetAmount != null) {
            body.put("budgetAmount", budgetAmount);
        }
        owner.as().body(body).put("/api/projects/" + projectId).then().statusCode(200);
    }

    /** Logs time and returns the id of the entry. */
    private static long log(Account who, Account owner, long projectId, int minutes, boolean billable) {
        Number id = who.as().body(Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", minutes, "billable", billable))
                .post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201).extract().path("id");
        return id.longValue();
    }

    /** The budget alerts somebody has, oldest first. */
    private static List<String> alerts(Account who) {
        List<String> newestFirst = who.as().get("/api/notifications?limit=200").then().statusCode(200)
                .extract().jsonPath().getList("findAll { it.type == 'BUDGET_ALERT' }.message");
        List<String> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    @Test
    void tellsOwnersAndManagersOncePerThreshold() {
        Account owner = Api.signup("Olaf");
        Account manager = Api.signup("Mina");
        Account member = Api.signup("Mark");
        Account viewer = Api.signup("Vito");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        Api.addMember(owner, viewer, "CLIENT", Api.createClient(owner, "Acme"));
        long projectId = project(owner, "HRS", 10, null);

        log(member, owner, projectId, 420, false);
        assertThat(alerts(owner)).isEmpty();

        // Eight of ten hours: the first threshold.
        long eighth = log(member, owner, projectId, 60, false);
        String at80 = "Project HRS has used 80% of its hours budget: 8 of 10 hours";
        assertThat(alerts(owner)).containsExactly(at80);
        assertThat(alerts(manager)).containsExactly(at80);
        owner.as().get("/api/notifications").then()
                .body("[0].type", equalTo("BUDGET_ALERT")).body("[0].projectId", equalTo((int) projectId))
                .body("[0].taskId", nullValue()).body("[0].actorName", nullValue()).body("[0].read", equalTo(false));
        // Members and client viewers are not told what a project has burned.
        assertThat(alerts(member)).isEmpty();
        assertThat(alerts(viewer)).isEmpty();

        // More time above a level that was announced says nothing.
        long half = log(member, owner, projectId, 30, false);
        assertThat(alerts(owner)).containsExactly(at80);

        // All of it: the second threshold, and again only once. The manager's own entry tells the manager too.
        long last = log(manager, owner, projectId, 90, false);
        String at100 = "Project HRS has used 100% of its hours budget: 10 of 10 hours";
        assertThat(alerts(owner)).containsExactly(at80, at100);
        assertThat(alerts(manager)).containsExactly(at80, at100);
        long over = log(member, owner, projectId, 120, false);
        assertThat(alerts(owner)).containsExactly(at80, at100);

        // Time is taken off again, down to seven hours. Nobody is told, and the level goes down with it.
        for (long entryId : List.of(over, last, half, eighth)) {
            owner.as().delete("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId).then().statusCode(204);
        }
        assertThat(alerts(owner)).containsExactly(at80, at100);
        // So reaching eighty percent again is news again.
        log(member, owner, projectId, 75, false);
        assertThat(alerts(owner)).containsExactly(at80, at100, "Project HRS has used 83% of its hours budget: 8.25 of 10 hours");
        assertThat(alerts(member)).isEmpty();
    }

    @Test
    void hoursAndMoneyAreAnnouncedSeparately() {
        Account owner = Api.signup("Nora");
        Account manager = Api.signup("Odin");
        Account member = Api.signup("Pepa");
        Api.addMember(owner, manager, "MANAGER");
        Api.addMember(owner, member, "MEMBER");
        owner.as().body(Map.of("defaultHourlyRate", 100)).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        // Twenty hours and a thousand: at 100 an hour the money runs out long before the hours do.
        long projectId = project(owner, "BOTH", 20, 1000);

        log(member, owner, projectId, 480, true);
        String money80 = "Project BOTH has used 80% of its money budget: 800.00 of 1000.00 EUR";
        assertThat(alerts(owner)).containsExactly(money80);
        assertThat(alerts(manager)).containsExactly(money80);
        assertThat(alerts(member)).isEmpty();

        // Time that is not billable moves the hours and not the money.
        log(member, owner, projectId, 480, false);
        String hours80 = "Project BOTH has used 80% of its hours budget: 16 of 20 hours";
        assertThat(alerts(owner)).containsExactly(money80, hours80);

        // One entry that takes both over the top is two messages, one for each budget.
        log(member, owner, projectId, 300, true);
        assertThat(alerts(owner)).containsExactly(money80, hours80,
                "Project BOTH has used 105% of its hours budget: 21 of 20 hours",
                "Project BOTH has used 130% of its money budget: 1300.00 of 1000.00 EUR");
        assertThat(alerts(member)).isEmpty();
    }

    @Test
    void aBudgetOrARateThatChangesIsLookedAtToo() {
        Account owner = Api.signup("Quin");
        Account member = Api.signup("Rhea");
        Api.addMember(owner, member, "MEMBER");
        owner.as().body(Map.of("defaultHourlyRate", 50)).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        long hours = project(owner, "CUT", 100, null);
        long money = project(owner, "RATE", null, 1000);
        log(member, owner, hours, 600, false);
        log(member, owner, money, 600, true);
        assertThat(alerts(owner)).isEmpty();

        // The budget is cut to twelve hours with ten on it, raised again, and cut to ten.
        budget(owner, hours, "CUT", 12, null);
        String cut = "Project CUT has used 83% of its hours budget: 10 of 12 hours";
        assertThat(alerts(owner)).containsExactly(cut);
        budget(owner, hours, "CUT", 100, null);
        budget(owner, hours, "CUT", 10, null);
        String cutAgain = "Project CUT has used 100% of its hours budget: 10 of 10 hours";
        assertThat(alerts(owner)).containsExactly(cut, cutAgain);
        // Saving the project without touching the budget says nothing.
        budget(owner, hours, "CUT", 10, null);
        assertThat(alerts(owner)).containsExactly(cut, cutAgain);

        // Ten hours at 50 are half the money. At 90 an hour they are nine tenths, without an hour being logged.
        owner.as().body(Map.of("defaultHourlyRate", 90)).patch("/api/orgs/" + owner.orgId()).then().statusCode(200);
        String at90 = "Project RATE has used 90% of its money budget: 900.00 of 1000.00 EUR";
        assertThat(alerts(owner)).containsExactly(cut, cutAgain, at90);
        // A lower rate for the role takes the burn back under the line, quietly. A rate for the person takes it over the top.
        owner.as().body(Map.of("rates", List.of(Map.of("role", "MEMBER", "hourlyRate", 70))))
                .put("/api/orgs/" + owner.orgId() + "/role-rates").then().statusCode(200);
        assertThat(alerts(owner)).containsExactly(cut, cutAgain, at90);
        Number membershipId = owner.as().get("/api/orgs/" + owner.orgId() + "/members").then()
                .extract().path("find { it.userId == " + member.userId() + " }.id");
        owner.as().body(Map.of("hourlyRate", 120)).patch("/api/orgs/" + owner.orgId() + "/members/" + membershipId).then().statusCode(200);
        assertThat(alerts(owner)).containsExactly(cut, cutAgain, at90,
                "Project RATE has used 120% of its money budget: 1200.00 of 1000.00 EUR");
    }

    @Test
    void aCreateThatIsSentAgainAlertsOnce() {
        Account owner = Api.signup("Sven");
        long projectId = project(owner, "TWICE", 10, null);
        Map<String, Object> create = Map.of("projectId", projectId, "date", WEEK.toString(), "minutes", 540, "clientKey", "budget-alert-key-1");

        owner.as().body(create).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        owner.as().body(create).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);
        owner.as().body(create).post("/api/orgs/" + owner.orgId() + "/time-entries").then().statusCode(201);

        assertThat(alerts(owner)).containsExactly("Project TWICE has used 90% of its hours budget: 9 of 10 hours");
    }

    @Test
    void movingAnEntryLooksAtBothProjectsAndAStoppedTimerCounts() {
        Account owner = Api.signup("Thea");
        long first = project(owner, "ONE", 10, null);
        long second = project(owner, "TWO", 10, null);
        String entries = "/api/orgs/" + owner.orgId() + "/time-entries/";
        long entryId = log(owner, owner, first, 480, false);
        String one = "Project ONE has used 80% of its hours budget: 8 of 10 hours";
        assertThat(alerts(owner)).containsExactly(one);

        // To the other project and back: each arrival is a crossing, each departure takes the level down.
        owner.as().body(Map.of("projectId", second, "date", WEEK.toString(), "minutes", 480)).put(entries + entryId).then().statusCode(200);
        String two = "Project TWO has used 80% of its hours budget: 8 of 10 hours";
        assertThat(alerts(owner)).containsExactly(one, two);
        owner.as().body(Map.of("projectId", first, "date", WEEK.toString(), "minutes", 480)).put(entries + entryId).then().statusCode(200);
        assertThat(alerts(owner)).containsExactly(one, two, one);
        // The same entry made longer is still above the same level.
        owner.as().body(Map.of("projectId", first, "date", WEEK.toString(), "minutes", 540)).put(entries + entryId).then().statusCode(200);
        assertThat(alerts(owner)).containsExactly(one, two, one);

        // A timer books at least a minute when it stops, which is more than this budget has.
        long tiny = project(owner, "TINY", 0.01, null);
        owner.as().body(Map.of("projectId", tiny)).post("/api/orgs/" + owner.orgId() + "/timer/start").then().statusCode(201);
        assertThat(alerts(owner)).hasSize(3);
        owner.as().post("/api/orgs/" + owner.orgId() + "/timer/stop").then().statusCode(200);
        assertThat(alerts(owner)).containsExactly(one, two, one, "Project TINY has used 200% of its hours budget: 0.02 of 0.01 hours");
    }

    @Test
    void aProjectWithoutABudgetNeverAlerts() {
        Account owner = Api.signup("Udo");
        long projectId = project(owner, "FREE", null, null);

        long entryId = log(owner, owner, projectId, 1440, true);
        log(owner, owner, projectId, 1440, true);
        owner.as().delete("/api/orgs/" + owner.orgId() + "/time-entries/" + entryId).then().statusCode(204);

        assertThat(alerts(owner)).isEmpty();
        QuarkusTransaction.requiringNew().run(() -> {
            Project project = Project.findById(projectId);
            assertThat(project.hoursAlertPct).isZero();
            assertThat(project.amountAlertPct).isZero();
        });
        // A budget that arrives later is compared with the time that is there already.
        budget(owner, projectId, "FREE", 25, null);
        assertThat(alerts(owner)).containsExactly("Project FREE has used 96% of its hours budget: 24 of 25 hours");
    }

    @Test
    void twoEntriesSavedAtTheSameMomentAreOneAlert() throws Exception {
        Account owner = Api.signup("Vera");
        Account member = Api.signup("Wolf");
        Api.addMember(owner, member, "MEMBER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                long projectId = project(owner, "RACE" + round, 10, null);
                log(owner, owner, projectId, 450, false);
                // Seven and a half hours are on it. Each of the two entries takes it past eight on its own.
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Long>> saved = new ArrayList<>();
                for (Account who : List.of(owner, member)) {
                    saved.add(pool.submit(() -> {
                        start.await();
                        return log(who, owner, projectId, 45, false);
                    }));
                }
                start.countDown();
                for (Future<Long> entry : saved) {
                    entry.get(30, TimeUnit.SECONDS);
                }
                String name = "Project RACE" + round + " ";
                long told = alerts(owner).stream().filter(message -> message.startsWith(name)).count();
                assertThat(told).as("alerts for round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        // The one that was told came first and saw its own entry. The second saw both and a level that was announced.
        assertThat(alerts(owner)).hasSize(6).allMatch(message -> message.endsWith("has used 83% of its hours budget: 8.25 of 10 hours"));
    }
}
