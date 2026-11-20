package com.projectplanner.seed;

import com.projectplanner.auth.User;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.support.TestClock;
import com.projectplanner.task.TaskDependency;
import com.projectplanner.time.TimeEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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

        // The demo plan has something to warn about: one task that was pulled in front of the task it
        // waits for, and the tail of the project that runs past the client's deadline.
        Long booking = QuarkusTransaction.requiringNew().call(() -> Project.<Project>find("code", "ACME-APP").firstResult().id);
        given().auth().oauth2(token).get("/api/projects/" + booking + "/schedule-issues").then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].type", equalTo("STARTS_TOO_EARLY"))
                .body("[0].days", equalTo(3))
                .body("[0].suggestedStart", notNullValue());
        Long portal = QuarkusTransaction.requiringNew().call(() -> Project.<Project>find("code", "GLX-PORTAL").firstResult().id);
        given().auth().oauth2(token).get("/api/projects/" + portal + "/schedule-issues").then().statusCode(200)
                .body("type", everyItem(equalTo("AFTER_PROJECT_END")))
                .body("$", hasSize(2));

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
}
