package com.projectplanner.seed;

import com.projectplanner.auth.User;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.task.TaskDependency;
import com.projectplanner.time.TimeEntry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
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
        QuarkusTransaction.requiringNew().run(seeder::seed);
        QuarkusTransaction.requiringNew().run(seeder::seed);

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
    }
}
