package com.projectplanner.org;

import com.projectplanner.auth.PasswordHasher;
import com.projectplanner.auth.User;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Mailbox;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** With INVITE_LINK_REQUIRED on, nobody signs up without the link of an invitation. */
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

    @Inject
    PasswordHasher hasher;

    @Test
    void nobodySignsUpWithoutTheLinkOfAnInvitation() {
        given().get("/api/auth/options").then().statusCode(200)
                .body("mail", equalTo(true)).body("inviteLinkRequired", equalTo(true));

        // Without any invitation at all.
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "Stranger", "email", Api.uniqueEmail("stranger"), "password", "correct-horse",
                        "organisationName", "Somewhere"))
                .post("/api/auth/signup").then().statusCode(409).body("code", equalTo("invitation_link_required"));
    }

    @Test
    void theLinkStillLetsTheInviteeIn() {
        Api.Account olga = owner();
        String email = Api.uniqueEmail("tess");
        InvitationMailTest.invite(olga, email, "MEMBER").statusCode(201);
        String token = new Mailbox(mockMailbox, outbox).lastToken(email);

        InvitationMailTest.signup(email, token).statusCode(201);
        Number joined = given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "correct-horse"))
                .post("/api/auth/login").then().statusCode(200).extract().path("user.id");
        given().auth().oauth2(olga.token()).get("/api/orgs/" + olga.orgId() + "/members").then()
                .body("userId", hasItem(joined.intValue()));
    }

    /** An owner with an organisation, made straight in the database, since signing up is closed here. */
    private Api.Account owner() {
        String email = Api.uniqueEmail("olga");
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            User user = new User();
            user.email = email;
            user.name = "Olga";
            user.passwordHash = hasher.hash("correct-horse");
            user.createdAt = Instant.now();
            user.persist();
            Organisation org = new Organisation();
            org.name = "Olga's studio";
            org.createdAt = Instant.now();
            org.persist();
            Membership.create(org.id, user.id, Role.OWNER, Instant.now());
            return new long[] {user.id, org.id};
        });
        String token = given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "correct-horse"))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");
        return new Api.Account(token, ids[0], email, "olga", ids[1]);
    }
}
