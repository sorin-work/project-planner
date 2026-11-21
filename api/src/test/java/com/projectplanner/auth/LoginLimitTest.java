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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LoginLimitTest {

    private static final String ATTACKER = "203.0.113.66";
    private static final String OWNER = "198.51.100.7";

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

    private static ValidatableResponse login(String email, String password, String address) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", address)
                .body(Map.of("email", email, "password", password)).post("/api/auth/login").then();
    }

    private static void fail(Api.Account account, int times, String address) {
        for (int i = 0; i < times; i++) {
            login(account.email(), "wrong-password", address).statusCode(401).body("code", equalTo("invalid_credentials"));
        }
    }

    private static int pairFailures(Api.Account account, String address) {
        return QuarkusTransaction.requiringNew().call(() -> {
            LoginFailure pair = LoginFailure.find("userId = ?1 and address = ?2", account.userId(), address).firstResult();
            return pair == null ? 0 : pair.failures;
        });
    }

    @Test
    void fiveWrongPasswordsFromOneAddressPassThenEvenTheRightOneFromThereWaits() {
        Api.Account ana = Api.signup("Ana");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        fail(ana, LoginLimits.PAIR_FREE, ATTACKER);

        // During the wait the password is not looked at: right and wrong get the same answer.
        login(ana.email(), "correct-horse", ATTACKER).statusCode(429)
                .header("Retry-After", "1")
                .body("code", equalTo("too_many_attempts"))
                .body("message", equalTo("Too many wrong passwords. Try again in 1 second."));
        login(ana.email(), "wrong-password", ATTACKER).statusCode(429).header("Retry-After", "1");

        TestClock.fix(start.plusSeconds(1));
        login(ana.email(), "correct-horse", ATTACKER).statusCode(200);
        // The right password forgot the failures: five more pass.
        fail(ana, LoginLimits.PAIR_FREE, ATTACKER);
        login(ana.email(), "wrong-password", ATTACKER).statusCode(429);
    }

    @Test
    void eachFurtherTryWaitsTwiceAsLongUpToFiveMinutesAndWaitingDoesNotAddToIt() {
        Api.Account ben = Api.signup("Ben");
        Instant now = Instant.now().plusSeconds(5);
        TestClock.fix(now);
        fail(ben, LoginLimits.PAIR_FREE, ATTACKER);

        List<String> waits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String wait = login(ben.email(), "wrong-password", ATTACKER).statusCode(429).extract().header("Retry-After");
            waits.add(wait);
            now = now.plusSeconds(Long.parseLong(wait));
            TestClock.fix(now);
            login(ben.email(), "wrong-password", ATTACKER).statusCode(401);
        }
        assertThat(waits).containsExactly("1", "2", "4", "8", "16", "32", "64", "128", "256", "300");

        // Tries during the wait are refused and do not push it further out.
        TestClock.fix(now.plusSeconds(100));
        login(ben.email(), "correct-horse", ATTACKER).statusCode(429).header("Retry-After", "200")
                .body("message", equalTo("Too many wrong passwords. Try again in 4 minutes."));
        TestClock.fix(now.plusSeconds(300));
        login(ben.email(), "correct-horse", ATTACKER).statusCode(200);
    }

    @Test
    void somebodyHammeringFromOneAddressNeverHoldsBackTheOwnerFromAnother() {
        Api.Account cy = Api.signup("Cy");
        Instant now = Instant.now().plusSeconds(5);
        TestClock.fix(now);
        fail(cy, LoginLimits.PAIR_FREE, ATTACKER);
        // Every try as the wait ends starts it again for that address.
        for (int i = 0; i < 8; i++) {
            now = now.plus(LoginLimits.waitAfter(LoginLimits.PAIR_FREE + i, LoginLimits.PAIR_FREE));
            TestClock.fix(now);
            login(cy.email(), "wrong-password", ATTACKER).statusCode(401);
            login(cy.email(), "correct-horse", ATTACKER).statusCode(429);
            // The owner, at the same moment, from somewhere else.
            login(cy.email(), "correct-horse", OWNER).statusCode(200);
        }
        assertThat(pairFailures(cy, ATTACKER)).isEqualTo(LoginLimits.PAIR_FREE + 8);
    }

    @Test
    void manyWrongPasswordsForAnAccountLeaveANewAddressOneFreeTryButNeverRefuseTheRightPassword() {
        Api.Account dee = Api.signup("Dee");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        for (int i = 0; i < LoginLimits.ACCOUNT_FREE / LoginLimits.PAIR_FREE; i++) {
            fail(dee, LoginLimits.PAIR_FREE, "203.0.113." + i);
        }
        // A new address gets one wrong try before it waits.
        login(dee.email(), "wrong-password", "203.0.113.200").statusCode(401);
        login(dee.email(), "wrong-password", "203.0.113.200").statusCode(429).header("Retry-After", "1");
        // The right password from a new address goes straight in, and the account starts over.
        login(dee.email(), "correct-horse", OWNER).statusCode(200);
        fail(dee, LoginLimits.PAIR_FREE, "203.0.113.201");
    }

    @Test
    void anAddressWithManyWrongPasswordsStillSignsInWithTheRightOne() {
        Api.Account eli = Api.signup("Eli");
        Api.Account fay = Api.signup("Fay");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        for (int i = 0; i < LoginLimits.ADDRESS_FREE; i++) {
            login(Api.uniqueEmail("spray"), "some-password", ATTACKER).statusCode(401);
        }
        // An office behind that address: the right password goes in at once.
        login(eli.email(), "correct-horse", ATTACKER).statusCode(200);
        // A wrong one gets one free try for the account before that pair waits.
        login(fay.email(), "wrong-password", ATTACKER).statusCode(401);
        login(fay.email(), "correct-horse", ATTACKER).statusCode(429);
        login(fay.email(), "correct-horse", OWNER).statusCode(200);
    }

    @Test
    void theCountIsKeptInTheDatabaseAndAResetClearsIt() {
        Api.Account gil = Api.signup("Gil");
        Mailbox mailbox = new Mailbox(mockMailbox, outbox);
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        fail(gil, LoginLimits.PAIR_FREE, ATTACKER);
        login(gil.email(), "correct-horse", ATTACKER).statusCode(429);

        // Not in memory: forgetting the addresses changes nothing.
        limits.clear();
        assertThat(pairFailures(gil, ATTACKER)).isEqualTo(LoginLimits.PAIR_FREE);
        login(gil.email(), "correct-horse", ATTACKER).statusCode(429);

        given().contentType(ContentType.JSON).body(Map.of("email", gil.email())).post("/api/auth/forgot").then().statusCode(204);
        String token = mailbox.lastToken(gil.email());
        given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "after-the-reset"))
                .post("/api/auth/reset").then().statusCode(204);
        assertThat(pairFailures(gil, ATTACKER)).isZero();
        login(gil.email(), "after-the-reset", ATTACKER).statusCode(200);
    }

    @Test
    void ofABurstOfTriesWhenTheWaitEndsOnlyOneIsLetThrough() throws Exception {
        Api.Account hal = Api.signup("Hal");
        Instant start = Instant.now().plusSeconds(5);
        TestClock.fix(start);
        fail(hal, LoginLimits.PAIR_FREE, ATTACKER);
        TestClock.fix(start.plusSeconds(1));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> login(hal.email(), "wrong-password", ATTACKER).extract().statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : pool.invokeAll(calls)) {
                statuses.add(status.get());
            }
            assertThat(statuses).containsOnlyOnce(401).containsOnly(401, 429);
        } finally {
            pool.shutdown();
        }
        assertThat(pairFailures(hal, ATTACKER)).isEqualTo(LoginLimits.PAIR_FREE + 1);
    }

    @Test
    void anUnknownEmailIsCheckedAgainstADummyHashLikeAWrongPassword() {
        Api.Account ida = Api.signup("Ida");
        hasher.takeChecked();

        login(Api.uniqueEmail("nobody"), "some-password", ATTACKER).statusCode(401).body("code", equalTo("invalid_credentials"));
        assertThat(hasher.takeChecked()).containsExactly(hasher.dummyHash());

        String stored = QuarkusTransaction.requiringNew().call(() -> User.<User>findById(ida.userId()).passwordHash);
        login(ida.email(), "wrong-password", ATTACKER).statusCode(401).body("code", equalTo("invalid_credentials"));
        assertThat(hasher.takeChecked()).containsExactly(stored);
        assertThat(hasher.dummyHash()).isNotEqualTo(stored).startsWith("$2");
    }
}
