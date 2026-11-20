package com.projectplanner.seed;

import com.projectplanner.auth.User;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.report.ReportRange;
import com.projectplanner.report.TimeReportService;
import com.projectplanner.report.TimeReportService.Filter;
import com.projectplanner.support.TestClock;
import com.projectplanner.task.TaskDependency;
import com.projectplanner.time.TimeEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class DemoSeederTest {

    @Inject
    DemoSeeder seeder;

    @Inject
    TimeReportService reports;

    @Test
    void seedsADemoOrganisationOnce() {
        // Seeded at half past midnight in the demo organisation, when "an hour ago" was yesterday there.
        TestClock.fix(LocalDate.now(DemoSeeder.ZONE).atTime(0, 30).atZone(DemoSeeder.ZONE).toInstant());
        try {
            QuarkusTransaction.requiringNew().run(seeder::seed);
            QuarkusTransaction.requiringNew().run(seeder::seed);
        } finally {
            TestClock.reset();
        }

        QuarkusTransaction.requiringNew().run(() -> {
            User demo = User.findByEmail(DemoSeeder.DEMO_EMAIL).orElseThrow();
            Membership membership = Membership.forUser(demo.id).getFirst();
            assertThat(membership.role).isEqualTo(Role.OWNER);
            assertThat(Membership.forOrganisation(membership.organisationId)).hasSize(6);
            assertThat(Project.count("organisationId", membership.organisationId)).isEqualTo(8);
            assertThat(TaskDependency.count()).isGreaterThan(40);
            LocalDate yearAgo = LocalDate.now().minusDays(340);
            assertThat(TimeEntry.count("organisationId = ?1 and entryDate < ?2", membership.organisationId, yearAgo)).isPositive();
            assertThat(TimeEntry.count("organisationId", membership.organisationId)).isGreaterThan(1000);
            // Timesheet notifications say which week they are about, the reminder included.
            assertThat(Notification.count("organisationId = ?1 and type = ?2 and weekStart is not null",
                    membership.organisationId, NotificationType.TIMESHEET_REMINDER)).isEqualTo(1);
            assertThat(Notification.count("organisationId = ?1 and type in ?2 and weekStart is null",
                    membership.organisationId, List.of(NotificationType.TIMESHEET_SUBMITTED, NotificationType.TIMESHEET_APPROVED,
                            NotificationType.TIMESHEET_REJECTED, NotificationType.TIMESHEET_REMINDER))).isZero();
        });

        String token = given().contentType(ContentType.JSON)
                .body(Map.of("email", DemoSeeder.DEMO_EMAIL, "password", DemoSeeder.DEMO_PASSWORD))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");
        Number orgId = given().auth().oauth2(token).get("/api/me").then().extract().path("organisations[0].organisationId");
        given().auth().oauth2(token).get("/api/orgs/" + orgId + "/projects").then().statusCode(200).body("$", hasSize(8));
        given().auth().oauth2(token).get("/api/orgs/" + orgId + "/dashboard").then().statusCode(200)
                .body("activeProjects", hasSize(4)).body("pendingApprovals", notNullValue());

        // Other tests may use the same codes in organisations of their own, so the lookups stay in the demo one.
        // The demo plan has something to warn about: one task that was pulled in front of the task it
        // waits for, and the tail of the project that runs past the client's deadline.
        Long booking = QuarkusTransaction.requiringNew().call(() -> Project.<Project>find("organisationId = ?1 and code = ?2", orgId.longValue(), "ACME-APP").firstResult().id);
        given().auth().oauth2(token).get("/api/projects/" + booking + "/schedule-issues").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].type", equalTo("STARTS_TOO_EARLY"))
                .body("[0].days", equalTo(3))
                .body("[0].suggestedStart", notNullValue());
        Long portal = QuarkusTransaction.requiringNew().call(() -> Project.<Project>find("organisationId = ?1 and code = ?2", orgId.longValue(), "GLX-PORTAL").firstResult().id);
        given().auth().oauth2(token).get("/api/projects/" + portal + "/schedule-issues").then().statusCode(200)
                .body("type", everyItem(equalTo("AFTER_PROJECT_END")))
                .body("$", hasSize(2));

        // The Acme plan has the baseline it was signed off with. Build and release are a week behind it,
        // and the loose end on the owner's plate came after it.
        Number baseline = given().auth().oauth2(token).get("/api/projects/" + booking + "/baselines").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].reference", equalTo(true)).extract().path("[0].id");
        given().auth().oauth2(token).get("/api/baselines/" + baseline + "/comparison").then().statusCode(200)
                .body("tasks.find { it.title == 'Launch' }.change", equalTo("SLIPPED"))
                .body("tasks.find { it.title == 'Launch' }.finishSlipDays", equalTo(5))
                .body("tasks.find { it.title == 'Kick-off and goals' }.change", equalTo("UNCHANGED"))
                .body("tasks.find { it.title == 'Agree beta test group with Ruth' }.change", equalTo("ADDED"))
                .body("planEnd.slipDays", equalTo(5));

        // The patient portal is over its hours budget and has told the two people who run projects. The other
        // projects start at the level their burn is at, so logging more time on them announces nothing old.
        var inbox = given().auth().oauth2(token).get("/api/notifications?limit=200").then().statusCode(200).extract().jsonPath();
        List<String> alerts = inbox.getList("findAll { it.type == 'BUDGET_ALERT' }.message");
        assertThat(alerts).isNotEmpty().allMatch(message -> message.startsWith("Globex patient portal has used 1"));
        assertThat(alerts).anyMatch(message -> message.contains("% of its hours budget: "));
        Number overBudget = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/projects").then()
                .extract().path("find { it.project.code == 'GLX-PORTAL' }.project.id");
        Number underBudget = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/projects").then()
                .extract().path("find { it.project.code == 'INT-DS' }.project.id");
        for (Number projectId : List.of(overBudget, underBudget)) {
            given().auth().oauth2(token).contentType(ContentType.JSON)
                    .body(Map.of("projectId", projectId, "date", LocalDate.now(DemoSeeder.ZONE).toString(), "minutes", 30))
                    .post("/api/orgs/" + orgId + "/time-entries").then().statusCode(201);
        }
        assertThat(given().auth().oauth2(token).get("/api/notifications?limit=200").then().extract().jsonPath()
                .<String>getList("findAll { it.type == 'BUDGET_ALERT' }.message")).isEqualTo(alerts);

        // Copies of the two plans, moved to next summer, say the same about their dates as the
        // originals: one task too early for its dependency, and two past the project's end.
        Number bookingCopy = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Acme booking app, season two", "code", "ACME-APP-2", "startDate", "2027-06-01"))
                .post("/api/projects/" + booking + "/duplicate").then().statusCode(201).extract().path("id");
        given().auth().oauth2(token).get("/api/projects/" + bookingCopy + "/schedule-issues").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].type", equalTo("STARTS_TOO_EARLY"))
                .body("[0].days", equalTo(3));
        Number portalCopy = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Globex portal, second clinic group", "code", "GLX-PORTAL-2", "startDate", "2027-06-05"))
                .post("/api/projects/" + portal + "/duplicate").then().statusCode(201).extract().path("id");
        given().auth().oauth2(token).get("/api/projects/" + portalCopy + "/schedule-issues").then().statusCode(200)
                .body("type", everyItem(equalTo("AFTER_PROJECT_END")))
                .body("days", equalTo(given().auth().oauth2(token).get("/api/projects/" + portal + "/schedule-issues")
                        .then().extract().path("days")));
        given().auth().oauth2(token).get("/api/orgs/" + orgId + "/projects").then().statusCode(200).body("$", hasSize(10));

        // Last week Tom has a draft and Samir a week that was sent back. Tom was reminded today, Samir was not.
        given().auth().oauth2(token).get("/api/orgs/" + orgId + "/timesheets/missing").then().statusCode(200)
                .body("people.userName", contains("Samir Haddad", "Tom Becker"))
                .body("people.remindedToday", contains(false, true));
    }

    /**
     * The reports over the seeded year, where the database does the adding up, against the CSV
     * export of the same year, which prices one entry at a time: to the minute and to the cent.
     */
    @Test
    void aYearOfReportsAddsUpToTheEntries() {
        QuarkusTransaction.requiringNew().run(seeder::seed);
        String token = given().contentType(ContentType.JSON)
                .body(Map.of("email", DemoSeeder.DEMO_EMAIL, "password", DemoSeeder.DEMO_PASSWORD))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");
        Number orgId = given().auth().oauth2(token).get("/api/me").then().extract().path("organisations[0].organisationId");
        LocalDate to = LocalDate.now(DemoSeeder.ZONE);
        LocalDate from = to.minusDays(365);
        String range = "?from=" + from + "&to=" + to;

        // member -> minutes, billable minutes, cents
        Map<String, long[]> exported = new HashMap<>();
        String[] lines = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/exports/timesheets.csv" + range)
                .then().statusCode(200).extract().asString().split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            // The last five columns hold no commas: hours, billable, timesheet_status, rate, amount.
            String[] cells = lines[i].split(",");
            int minutes = new BigDecimal(cells[cells.length - 5]).multiply(BigDecimal.valueOf(60)).intValueExact();
            long[] sums = exported.computeIfAbsent(cells[2], k -> new long[3]);
            sums[0] += minutes;
            sums[1] += cells[cells.length - 4].equals("yes") ? minutes : 0;
            sums[2] += new BigDecimal(cells[cells.length - 1]).movePointRight(2).longValueExact();
        }
        assertThat(lines.length).isGreaterThan(1000);
        assertThat(exported).hasSize(5);

        var byMember = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/reports/time" + range + "&groupBy=member")
                .then().statusCode(200).body("rows", hasSize(5)).extract().jsonPath();
        long minutes = 0;
        long billable = 0;
        long cents = 0;
        for (Map<String, Object> row : byMember.<Map<String, Object>>getList("rows")) {
            long[] sums = exported.get((String) row.get("label"));
            assertThat(((Number) row.get("minutes")).longValue()).as("minutes of %s", row.get("label")).isEqualTo(sums[0]);
            assertThat(((Number) row.get("billableMinutes")).longValue()).isEqualTo(sums[1]);
            assertThat(cents(row.get("billableAmount"))).as("amount of %s", row.get("label")).isEqualTo(sums[2]);
            minutes += sums[0];
            billable += sums[1];
            cents += sums[2];
        }
        assertThat(byMember.getLong("total.minutes")).isEqualTo(minutes);
        assertThat(byMember.getLong("total.billableMinutes")).isEqualTo(billable);
        assertThat(cents(byMember.get("total.billableAmount"))).isEqualTo(cents).isPositive();

        // Grouped another way the year comes to the same, and every total is the sum of its rows.
        for (String groupBy : List.of("project", "client", "task")) {
            var report = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/reports/time" + range + "&groupBy=" + groupBy)
                    .then().statusCode(200).extract().jsonPath();
            assertThat(report.getLong("total.minutes")).isEqualTo(minutes);
            assertThat(cents(report.get("total.billableAmount"))).isEqualTo(cents);
            assertThat(report.<Object>getList("rows.billableAmount").stream().mapToLong(DemoSeederTest::cents).sum()).isEqualTo(cents);
            assertThat(report.<Number>getList("rows.minutes").stream().mapToLong(Number::longValue).sum()).isEqualTo(minutes);
        }
        var utilisation = given().auth().oauth2(token).get("/api/orgs/" + orgId + "/reports/utilisation" + range)
                .then().statusCode(200).body("rows", hasSize(5)).extract().jsonPath();
        assertThat(utilisation.getLong("total.minutes")).isEqualTo(minutes);
        assertThat(utilisation.getLong("total.billableMinutes")).isEqualTo(billable);

        // The year reaches the report as groups, not as its entries.
        QuarkusTransaction.requiringNew().run(() -> {
            long entries = TimeEntry.count("organisationId = ?1 and entryDate between ?2 and ?3", orgId.longValue(), from, to);
            int groups = reports.buckets(orgId.longValue(), new ReportRange(from, to), false, new Filter(null, null, null, null), null).size();
            assertThat(entries).isGreaterThan(1000);
            assertThat((long) groups * 3).as("%d groups for %d entries", groups, entries).isLessThan(entries);
        });
    }

    private static long cents(Object amount) {
        return new BigDecimal(amount.toString()).movePointRight(2).longValueExact();
    }
}
