package com.projectplanner.org;

import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Mailbox;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/** With INVITE_LINK_REQUIRED on, typing an invited address at sign-up joins nothing. */
@QuarkusTest
@TestProfile(InviteLinkRequiredTest.Required.class)
class InviteLinkRequiredTest {

    public static class Required implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("planner.signup.invite-link-required", "true");
        }
    }

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    @Test
    void anInvitedAddressJoinsOnlyWithTheLinkAndAnAccountAcceptsItAfterSigningIn() {
        given().get("/api/auth/options").then().statusCode(200)
                .body("mail", equalTo(true)).body("inviteLinkRequired", equalTo(true));

        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("tess");
        InvitationMailTest.invite(olga, email, "MEMBER").statusCode(201);
        String token = new Mailbox(mockMailbox, outbox).lastToken(email);

        // Signing up with the address alone: a workspace of one's own, not Olga's.
        Api.Account tess = Api.signup("Tess", email, null);
        tess.as().get("/api/me").then().body("organisations", hasSize(1)).body("organisations[0].role", equalTo("OWNER"));

        InvitationMailTest.readLink(token).statusCode(200).body("hasAccount", equalTo(true));
        tess.as().body(Map.of("token", token)).post("/api/invitations/accept").then().statusCode(200)
                .body("organisationId", equalTo((int) olga.orgId()))
                .body("role", equalTo("MEMBER"));
        tess.as().get("/api/me").then().body("organisations", hasSize(2))
                .body("organisations.organisationId", hasItem((int) olga.orgId()));

        tess.as().body(Map.of("token", token)).post("/api/invitations/accept").then()
                .statusCode(400).body("code", equalTo("invalid_link"));
        given().contentType(ContentType.JSON).body(Map.of("email", email)).get("/api/me").then().statusCode(401);
    }
}
