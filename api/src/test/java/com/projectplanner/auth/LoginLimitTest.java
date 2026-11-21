package com.projectplanner.auth;

import com.projectplanner.mail.ClientThrottle;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.CountingHasher;
import com.projectplanner.support.Mailbox;
import com.projectplanner.support.TestClock;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LoginLimitTest {

    @Inject
    LoginLimits limits;

    @Inject
    ClientThrottle throttle;

    @Inject
    CountingHasher hasher;

    @Inject
    MockMailbox mockMailbox;

    @Inject
    MailOutbox outbox;

    @BeforeEach
    @AfterEach
    void forget() {
        TestClock.reset();
        limits.clear();
        throttle.clear();
    }

    private static ValidatableResponse login(String email, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
                .post("/api/auth/login").then();
    }

    private static void fail(Api.Account account, int times) {
        for (int i = 0; i < times; i++) {
            login(account.email(), "wrong-password").statusCode(401).body("code", equalTo("invalid_credentials"));
        }
    }

    private static int failures(Api.Account account) {
        return QuarkusTransaction.requiringNew().call(() -> User.<User>findById(account.userId()).failedLogins);
    }

    @Test
    void fiveWrongPasswordsPassThenEvenTheRightOneWaitsItsTurn() {
        Api.Account ana = Api.signup("Ana");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        fail(ana, LoginLimits.ACCOUNT_FREE);

        // During the wait the password is not looked at: right and wrong get the same answer.
        login(ana.email(), "correct-horse").statusCode(429)
                .header("Retry-After", "1")
                .body("code", equalTo("too_many_attempts"))
                .body("message", equalTo("Too many wrong passwords. Try again in 1 second."));
        login(ana.email(), "wrong-password").statusCode(429).header("Retry-After", "1");

        TestClock.fix(start.plusSeconds(1));
        login(ana.email(), "correct-horse").statusCode(200);
        // The right password set the count back: five more pass.
        fail(ana, LoginLimits.ACCOUNT_FREE);
        login(ana.email(), "wrong-password").statusCode(429);
    }

    @Test
    void eachFurtherTryWaitsTwiceAsLongUpToFiveMinutesAndWaitingDoesNotAddToIt() {
        Api.Account ben = Api.signup("Ben");
        Instant now = Instant.now().plusSeconds(5);
        TestClock.fix(now);
        fail(ben, LoginLimits.ACCOUNT_FREE);

        List<String> waits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String wait = login(ben.email(), "wrong-password").statusCode(429).extract().header("Retry-After");
            waits.add(wait);
            now = now.plusSeconds(Long.parseLong(wait));
            TestClock.fix(now);
            login(ben.email(), "wrong-password").statusCode(401);
        }
        assertThat(waits).containsExactly("1", "2", "4", "8", "16", "32", "64", "128", "256", "300");

        // Tries during the wait are refused and do not push it further out.
        TestClock.fix(now.plusSeconds(100));
        login(ben.email(), "correct-horse").statusCode(429).header("Retry-After", "200")
                .body("message", equalTo("Too many wrong passwords. Try again in 4 minutes."));
        TestClock.fix(now.plusSeconds(300));
        login(ben.email(), "correct-horse").statusCode(200);
    }

    @Test
    void theCountIsKeptWithTheAccountAndAResetClearsIt() {
        Api.Account cara = Api.signup("Cara");
        Mailbox mailbox = new Mailbox(mockMailbox, outbox);
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        fail(cara, LoginLimits.ACCOUNT_FREE);
        Instant now = start;
        for (int i = 0; i < 3; i++) {
            now = now.plus(LoginLimits.waitAfter(LoginLimits.ACCOUNT_FREE + i, LoginLimits.ACCOUNT_FREE));
            TestClock.fix(now);
            login(cara.email(), "wrong-password").statusCode(401);
        }
        login(cara.email(), "correct-horse").statusCode(429).header("Retry-After", "8");

        // In the database, not in memory: forgetting the addresses changes nothing.
        limits.clear();
        assertThat(failures(cara)).isEqualTo(LoginLimits.ACCOUNT_FREE + 3);
        login(cara.email(), "correct-horse").statusCode(429);

        given().contentType(ContentType.JSON).body(Map.of("email", cara.email())).post("/api/auth/forgot").then().statusCode(204);
        String token = mailbox.lastToken(cara.email());
        given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "after-the-reset"))
                .post("/api/auth/reset").then().statusCode(204);
        login(cara.email(), "after-the-reset").statusCode(200);
    }

    @Test
    void oneAddressWaitsAfterTwentyWrongPasswordsWhateverTheAccount() {
        Api.Account dan = Api.signup("Dan");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE; i++) {
            login(Api.uniqueEmail("spray"), "some-password").statusCode(401);
        }
        login(dan.email(), "correct-horse").statusCode(429).header("Retry-After", "1");
        assertThat(failures(dan)).as("not counted against the account").isZero();
        TestClock.fix(start.plusSeconds(1));
        login(dan.email(), "correct-horse").statusCode(200);
    }

    @Test
    void anUnknownEmailIsCheckedAgainstADummyHashLikeAWrongPassword() {
        Api.Account eva = Api.signup("Eva");
        hasher.takeChecked();

        login(Api.uniqueEmail("nobody"), "some-password").statusCode(401).body("code", equalTo("invalid_credentials"));
        assertThat(hasher.takeChecked()).containsExactly(hasher.dummyHash());

        String stored = QuarkusTransaction.requiringNew().call(() -> User.<User>findById(eva.userId()).passwordHash);
        login(eva.email(), "wrong-password").statusCode(401).body("code", equalTo("invalid_credentials"));
        assertThat(hasher.takeChecked()).containsExactly(stored);
        assertThat(hasher.dummyHash()).isNotEqualTo(stored).startsWith("$2");
    }
}
