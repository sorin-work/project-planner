package com.projectplanner.mail;

import com.projectplanner.support.Api;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/** Without MAIL_HOST the app sends nothing and behaves as it did before mail. */
@QuarkusTest
@TestProfile(MailOffTest.Off.class)
class MailOffTest {

    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Required too, so that INVITE_LINK_REQUIRED is seen to need mail as well.
            return Map.of("planner.mail.host", "", "planner.signup.invite-link-required", "true");
        }
    }

    @Inject
    MockMailbox mailbox;

    @Inject
    MailOutbox outbox;

    @Test
    void nothingIsMailedAndTheMailEndpointsSaySo() {
        given().get("/api/auth/options").then().statusCode(200)
                .body("mail", equalTo(false)).body("inviteLinkRequired", equalTo(false));
        given().contentType(ContentType.JSON).body(Map.of("email", "someone@example.com"))
                .post("/api/auth/forgot").then().statusCode(404).body("code", equalTo("mail_off"));
        given().contentType(ContentType.JSON).body(Map.of("token", "any-token", "password", "new-password"))
                .post("/api/auth/reset").then().statusCode(404).body("code", equalTo("mail_off"));

        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("una");
        Number id = olga.as().body(Map.of("email", email, "role", "MEMBER"))
                .post("/api/orgs/" + olga.orgId() + "/invitations").then().statusCode(201)
                .body("invitation.linkExpiresAt", nullValue())
                .extract().path("invitation.id");
        olga.as().post("/api/orgs/" + olga.orgId() + "/invitations/" + id + "/resend").then()
                .statusCode(404).body("code", equalTo("mail_off"));

        // Signing up with the invited address is how one joins, as before.
        Api.Account una = Api.signup("Una", email, null);
        una.as().get("/api/me").then().body("organisations", hasSize(1))
                .body("organisations[0].organisationId", equalTo((int) olga.orgId()));

        outbox.awaitIdle();
        assertThat(mailbox.getTotalMessagesSent()).isZero();
    }
}
