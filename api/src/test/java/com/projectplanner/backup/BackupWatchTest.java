package com.projectplanner.backup;

import com.projectplanner.mail.MailOutbox;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BackupWatchTest {

    /** Where the test profile points BACKUP_STATUS_FILE. The profile names ops-one and ops-two as the operators. */
    private static final Path STATUS = Path.of("target/test-backup/backup-status");
    private static final long DAY = 86400;
    private static Account operator;

    @Inject
    BackupWatch watch;

    @Inject
    MockMailbox mailbox;

    @Inject
    MailOutbox outbox;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> BackupAlertMark.deleteAll());
        outbox.awaitIdle();
        mailbox.clear();
    }

    @AfterEach
    void removeStatus() throws IOException {
        Files.deleteIfExists(STATUS);
    }

    /** The account of ops-one, made once: an address can only sign up once. */
    private static synchronized Account operator() {
        if (operator == null) {
            operator = Api.signup("Ops One", "ops-one@example.com", "Operations");
        }
        return operator;
    }

    private static void status(String outcome, String dump, long goodAgo) throws IOException {
        long now = Instant.now().getEpochSecond();
        Files.createDirectories(STATUS.getParent());
        Files.writeString(STATUS, "last_run_at=" + now + "\nlast_outcome=" + outcome + "\nlast_good=" + dump + "\nlast_good_at="
                + (now - goodAgo) + "\ninterval=" + DAY + "\n");
    }

    private List<Mail> alerts(String to) {
        outbox.awaitIdle();
        return mailbox.getMailsSentTo(to) == null ? List.of() : mailbox.getMailsSentTo(to).stream()
                .filter(m -> m.getSubject().equals("The backups of project-planner need a look")).toList();
    }

    @Test
    void onlyTheOperatorsSeeTheStatus() throws IOException {
        Account ops = operator();
        Account owner = Api.signup("Ola Owner");

        ops.as().get("/api/backup-status").then().statusCode(200).body("watched", equalTo(true)).body("state", nullValue());
        status("ok", "planner-x.dump", 3600);
        ops.as().get("/api/backup-status").then().statusCode(200).body("state", equalTo("OK"))
                .body("lastGood", equalTo("planner-x.dump")).body("lastOutcome", equalTo("ok"));
        // An owner of an organisation is not who runs the server.
        owner.as().get("/api/backup-status").then().statusCode(200).body("watched", equalTo(false))
                .body("state", nullValue()).body("lastGood", nullValue());
    }

    @Test
    void theOperatorsAreMailedOncePerStateAndDump() throws IOException {
        status("ok", "planner-a.dump", 3600);
        assertThat(watch.check()).isFalse();

        // The copy of a fresh dump failed: one mail to each operator, whether or not they have an account.
        status("copy_failed", "planner-a.dump", 3600);
        assertThat(watch.check()).isTrue();
        assertThat(watch.check()).isFalse();
        assertThat(alerts("ops-one@example.com")).hasSize(1);
        assertThat(alerts("ops-two@example.com")).hasSize(1);
        assertThat(alerts("ops-one@example.com").getFirst().getText()).contains("could not be copied off the machine");

        // Later the same dump is the newest good one for too long: that is news, not hidden by the first mail.
        status("dump_failed", "planner-a.dump", 3 * DAY);
        assertThat(watch.check()).isTrue();
        assertThat(alerts("ops-one@example.com")).hasSize(2);

        // A dump dated in the future is told once, not every five minutes.
        status("ok", "planner-z.dump", -5 * DAY);
        assertThat(watch.check()).isTrue();
        assertThat(watch.check()).isFalse();
        assertThat(alerts("ops-one@example.com")).hasSize(3);
        assertThat(alerts("ops-one@example.com").getLast().getText()).contains("dated in the future");
    }

    @Test
    void nothingWithoutAStatusFileOrBeforeTheFirstDump() throws IOException {
        assertThat(watch.check()).isFalse();
        Files.createDirectories(STATUS.getParent());
        Files.writeString(STATUS, "not a status file");
        assertThat(watch.check()).isFalse();
        status("dump_failed", "", 0);
        Files.writeString(STATUS, Files.readString(STATUS).replaceAll("last_good_at=\\d+", "last_good_at="));
        assertThat(watch.check()).isFalse();
        assertThat(alerts("ops-one@example.com")).isEmpty();
    }

    @Test
    void readsTheListOfOperators() {
        assertThat(BackupWatch.addresses("A@example.com, b@example.com  c@example.com,,not-an-address"))
                .containsExactly("a@example.com", "b@example.com", "c@example.com");
        assertThat(watch.isOperator("OPS-ONE@example.com")).isTrue();
        assertThat(watch.isOperator("someone@example.com")).isFalse();
        assertThat(watch.isOperator(null)).isFalse();
    }
}
