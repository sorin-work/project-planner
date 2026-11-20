package com.projectplanner.org;

import com.projectplanner.auth.PasswordHasher;
import com.projectplanner.auth.User;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Mailbox;
import com.projectplanner.support.TestClock;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class InvitationMailTest {

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    @Inject
    PasswordHasher hasher;

    private Mailbox mailbox;

    @BeforeEach
    void setUp() {
        mailbox = new Mailbox(mockMailbox, outbox);
    }

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    static ValidatableResponse invite(Api.Account owner, String email, String role) {
        return owner.as().body(Map.of("email", email, "role", role))
                .post("/api/orgs/" + owner.orgId() + "/invitations").then();
    }

    static ValidatableResponse readLink(String token) {
        return given().contentType(ContentType.JSON).body(Map.of("token", token)).post("/api/auth/invitation").then();
    }

    static ValidatableResponse signup(String email, String token) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("name", "Nina Invited", "email", email, "password", "correct-horse", "invitationToken", token))
                .post("/api/auth/signup").then();
    }

    @Test
    void theMailHasALinkThatJoinsTheOrganisationWithTheRoleOfTheInvitation() {
        Api.Account olga = Api.signup("Olga Owner", Api.uniqueEmail("olga"), "Brightline Studio");
        String email = Api.uniqueEmail("nina");
        invite(olga, email, "MANAGER").statusCode(201).body("invitation.linkExpiresAt", notNullValue());

        Mail mail = mailbox.only(email);
        String token = Mailbox.token(mail);
        assertThat(mail.getText()).contains("http://planner.test/invite#token=" + token).contains("seven days");
        assertThat(mail.getText() + mail.getHtml() + mail.getSubject()).doesNotContain("Olga").doesNotContain("Brightline");

        readLink(token).statusCode(200)
                .body("email", equalTo(email))
                .body("organisationName", equalTo("Brightline Studio"))
                .body("role", equalTo("MANAGER"))
                .body("hasAccount", equalTo(false));

        // The link only works for the address it was sent to.
        signup(Api.uniqueEmail("someone.else"), token).statusCode(400).body("code", equalTo("invalid_link"));

        String nina = signup(email, token).statusCode(201).extract().path("token");
        given().auth().oauth2(nina).get("/api/me").then().statusCode(200)
                .body("organisations", hasSize(1))
                .body("organisations[0].organisationId", equalTo((int) olga.orgId()))
                .body("organisations[0].role", equalTo("MANAGER"));

        readLink(token).statusCode(400).body("code", equalTo("invalid_link"));
    }

    @Test
    void resendingOrCancellingKillsTheLinkAtOnce() {
        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("pat");
        Number id = invite(olga, email, "MEMBER").statusCode(201).extract().path("invitation.id");
        String path = "/api/orgs/" + olga.orgId() + "/invitations/" + id;
        String first = mailbox.lastToken(email);

        olga.as().post(path + "/resend").then().statusCode(409).body("code", equalTo("resend_too_soon"));

        TestClock.fix(Instant.now().plusSeconds(61));
        olga.as().post(path + "/resend").then().statusCode(200).body("id", equalTo(id.intValue()));
        String second = mailbox.lastToken(email);
        assertThat(mailbox.to(email)).hasSize(2);
        assertThat(second).isNotEqualTo(first);
        readLink(first).statusCode(400).body("code", equalTo("invalid_link"));
        readLink(second).statusCode(200);

        olga.as().delete(path).then().statusCode(204);
        readLink(second).statusCode(400).body("code", equalTo("invalid_link"));
        signup(email, second).statusCode(400).body("code", equalTo("invalid_link"));
    }

    @Test
    void onlyThoseWhoManageMembersCanResend() {
        Api.Account olga = Api.signup("Olga");
        Api.Account mia = Api.signup("Mia");
        Api.addMember(olga, mia, "MEMBER");
        Number id = invite(olga, Api.uniqueEmail("quinn"), "MEMBER").statusCode(201).extract().path("invitation.id");

        mia.as().post("/api/orgs/" + olga.orgId() + "/invitations/" + id + "/resend").then().statusCode(403);
    }

    @Test
    void someoneWithAnAccountIsAddedAndToldWithoutALink() {
        Api.Account olga = Api.signup("Olga", Api.uniqueEmail("olga"), "Brightline Studio");
        Api.Account ray = Api.signup("Ray");
        invite(olga, ray.email(), "MEMBER").statusCode(201).body("member.userId", equalTo((int) ray.userId()));

        Mail mail = mailbox.only(ray.email());
        assertThat(mail.getText()).contains("http://planner.test/login").doesNotContain("#token=");
        assertThat(mail.getText() + mail.getHtml() + mail.getSubject()).doesNotContain("Olga").doesNotContain("Brightline");
    }

    @Test
    void anotherAccountCannotAcceptTheLinkAndLearnsNothingFromTrying() {
        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("sid");
        invite(olga, email, "MEMBER").statusCode(201);
        String token = mailbox.lastToken(email);

        Api.Account intruder = Api.signup("Ivo");
        intruder.as().body(Map.of("token", token)).post("/api/invitations/accept").then()
                .statusCode(400).body("code", equalTo("invalid_link"));
        intruder.as().body(Map.of("token", "made-up-token")).post("/api/invitations/accept").then()
                .statusCode(400).body("code", equalTo("invalid_link"));
        // Trying did not use the link up.
        readLink(token).statusCode(200).body("hasAccount", equalTo(false));
    }

    @Test
    void signingUpWithTheInvitedAddressAloneMakesAnAccountAndLeavesTheInvitationWaiting() {
        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("victim");
        invite(olga, email, "MANAGER").statusCode(201);
        String token = mailbox.lastToken(email);

        // Somebody who only knows the address gets an account, but not the invitation.
        Api.Account mallory = Api.signup("Mallory", email, null);
        mallory.as().get("/api/me").then().body("organisations", hasSize(1))
                .body("organisations[0].role", equalTo("OWNER"))
                .body("organisations[0].organisationId", not(equalTo((int) olga.orgId())));
        olga.as().get("/api/orgs/" + olga.orgId() + "/invitations").then().body("email", hasItem(email));

        // Whoever reads the mail at that address joins with the link, after signing in.
        readLink(token).statusCode(200).body("hasAccount", equalTo(true));
        mallory.as().body(Map.of("token", token)).post("/api/invitations/accept").then().statusCode(200)
                .body("organisationId", equalTo((int) olga.orgId())).body("role", equalTo("MANAGER"));
    }

    @Test
    void anInvitationCannotLockAnAddressOutOfSigningUp() {
        // Somebody invites an address they do not own, before its owner turns up.
        Api.Account stranger = Api.signup("Stranger");
        String email = Api.uniqueEmail("owner.of.the.address");
        invite(stranger, email, "MEMBER").statusCode(201);

        Api.Account owner = Api.signup("Real Owner", email, "Own Studio");
        owner.as().get("/api/me").then().body("organisations", hasSize(1))
                .body("organisations[0].organisationName", equalTo("Own Studio"));
    }

    @Test
    void anAccountWithTheInvitedAddressAcceptsTheLinkAfterSigningIn() {
        Api.Account olga = Api.signup("Olga");
        String email = Api.uniqueEmail("wes");
        invite(olga, email, "MEMBER").statusCode(201);
        String token = mailbox.lastToken(email);
        // The account came about some other way after the invitation, here straight in the database.
        QuarkusTransaction.requiringNew().run(() -> {
            User user = new User();
            user.email = email;
            user.name = "Wes";
            user.passwordHash = hasher.hash("correct-horse");
            user.createdAt = Instant.now();
            user.persist();
        });
        String wes = given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "correct-horse"))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");

        readLink(token).statusCode(200).body("hasAccount", equalTo(true));
        given().auth().oauth2(wes).contentType(ContentType.JSON).body(Map.of("token", token))
                .post("/api/invitations/accept").then().statusCode(200)
                .body("organisationId", equalTo((int) olga.orgId())).body("role", equalTo("MEMBER"));
        given().auth().oauth2(wes).contentType(ContentType.JSON).body(Map.of("token", token))
                .post("/api/invitations/accept").then().statusCode(400).body("code", equalTo("invalid_link"));
    }
}
