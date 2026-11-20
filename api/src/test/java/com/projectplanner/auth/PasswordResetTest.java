package com.projectplanner.auth;

import com.projectplanner.mail.ClientThrottle;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Mailbox;
import com.projectplanner.support.TestClock;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class PasswordResetTest {

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    @Inject
    ClientThrottle throttle;

    private Mailbox mailbox;

    @BeforeEach
    void setUp() {
        mailbox = new Mailbox(mockMailbox, outbox);
        outbox.awaitIdle();
        mockMailbox.clear();
        throttle.clear();
    }

    @AfterEach
    void resetClock() {
        TestClock.reset();
    }

    private static ValidatableResponse forgot(String email) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email)).post("/api/auth/forgot").then();
    }

    private static ValidatableResponse reset(String token, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("token", token, "password", password)).post("/api/auth/reset").then();
    }

    private static ValidatableResponse login(String email, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email, "password", password)).post("/api/auth/login").then();
    }

    @Test
    void aLinkSetsANewPasswordOnceAndEndsEverySessionStartedBefore() {
        Api.Account rita = Api.signup("Rita Moreno");
        // Everything below happens in one and the same second, the way a script that signs in
        // again and again would try to keep a session alive across the reset.
        TestClock.fix(Instant.now());
        String sameSecond = login(rita.email(), "correct-horse").statusCode(200).extract().path("token");
        forgot(rita.email()).statusCode(204);

        Mail mail = mailbox.only(rita.email());
        String token = Mailbox.token(mail);
        assertThat(mail.getSubject()).isEqualTo("Reset your project-planner password");
        assertThat(mail.getText()).contains("http://planner.test/reset#token=" + token).contains("one hour").doesNotContain("Rita");
        assertThat(mail.getHtml()).contains("href=\"http://planner.test/reset#token=" + token + "\"").doesNotContain("Rita");
        assertThat(mail.getFrom()).isEqualTo("planner@example.com");

        reset(token, "brand-new-password").statusCode(204);

        // Both the endpoints that load the user and those that only ask for the id.
        rita.as().get("/api/me").then().statusCode(401).body("code", equalTo("unauthorized"));
        rita.as().get("/api/orgs/" + rita.orgId()).then().statusCode(401);
        given().auth().oauth2(sameSecond).get("/api/orgs/" + rita.orgId()).then().statusCode(401);
        login(rita.email(), "correct-horse").statusCode(401);
        // Signed in within the same second as the reset, with the new password: that works.
        String fresh = login(rita.email(), "brand-new-password").statusCode(200).extract().path("token");
        given().auth().oauth2(fresh).get("/api/orgs/" + rita.orgId()).then().statusCode(200);

        reset(token, "yet-another-password").statusCode(400).body("code", equalTo("invalid_link"));
    }

    @Test
    void anAddressWithoutAnAccountGetsTheSameAnswerAndNoMail() {
        Api.Account sam = Api.signup("Sam");
        String nobody = Api.uniqueEmail("nobody");

        var known = forgot(sam.email()).statusCode(204).extract();
        var unknown = forgot(nobody).statusCode(204).extract();

        assertThat(unknown.body().asString()).isEqualTo(known.body().asString());
        assertThat(unknown.contentType()).isEqualTo(known.contentType());
        assertThat(mailbox.to(sam.email())).hasSize(1);
        assertThat(mailbox.to(nobody)).isEmpty();
    }

    @Test
    void askingAgainKeepsEarlierLinksWithAtMostOneAMinuteAndThreeThatWork() {
        Api.Account tom = Api.signup("Tom");
        Instant start = Instant.now().plusSeconds(5);

        TestClock.fix(start);
        forgot(tom.email()).statusCode(204);
        forgot(tom.email()).statusCode(204);
        assertThat(mailbox.to(tom.email())).as("a second link within a minute").hasSize(1);
        String first = mailbox.lastToken(tom.email());

        TestClock.fix(start.plusSeconds(61));
        forgot(tom.email()).statusCode(204);
        String second = mailbox.lastToken(tom.email());
        TestClock.fix(start.plusSeconds(122));
        forgot(tom.email()).statusCode(204);
        TestClock.fix(start.plusSeconds(183));
        forgot(tom.email()).statusCode(204);
        assertThat(mailbox.to(tom.email())).as("no fourth link while three work").hasSize(3);

        // The first link was not cancelled by the later ones. Using it ends the others.
        reset(first, "first-link-password").statusCode(204);
        reset(second, "second-link-password").statusCode(400).body("code", equalTo("invalid_link"));
        login(tom.email(), "first-link-password").statusCode(200);

        // With those used up, a new one can be sent.
        TestClock.fix(start.plusSeconds(244));
        forgot(tom.email()).statusCode(204);
        assertThat(mailbox.to(tom.email())).hasSize(4);
    }

    @Test
    void aLinkStopsWorkingAfterAnHour() {
        Api.Account uma = Api.signup("Uma");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        forgot(uma.email()).statusCode(204);
        String token = mailbox.lastToken(uma.email());

        TestClock.fix(start.plusSeconds(3601));
        reset(token, "too-late-password").statusCode(400).body("code", equalTo("invalid_link"));
        login(uma.email(), "correct-horse").statusCode(200);
    }

    @Test
    void ofTwoResetsWithTheSameLinkAtOnceOnlyOneGetsThrough() throws Exception {
        Api.Account vera = Api.signup("Vera");
        forgot(vera.email()).statusCode(204);
        String token = mailbox.lastToken(vera.email());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String password = "parallel-password-" + i;
                calls.add(() -> reset(token, password).extract().statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : pool.invokeAll(calls)) {
                statuses.add(status.get());
            }
            assertThat(statuses).containsOnlyOnce(204).containsOnly(204, 400);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void oneClientIsHeldBackAfterTenRequestsInAQuarterOfAnHour() {
        for (int i = 0; i < 10; i++) {
            forgot(Api.uniqueEmail("flood")).statusCode(204);
        }
        forgot(Api.uniqueEmail("flood")).statusCode(429).body("code", equalTo("too_many_requests"));
    }

    @Test
    void changingThePasswordAlsoUsesUpTheResetLinks() {
        Api.Account will = Api.signup("Will");
        forgot(will.email()).statusCode(204);
        String token = mailbox.lastToken(will.email());

        will.as().body(Map.of("currentPassword", "correct-horse", "newPassword", "changed-password"))
                .post("/api/me/password").then().statusCode(200);
        reset(token, "reset-password-1").statusCode(400).body("code", equalTo("invalid_link"));
    }
}
