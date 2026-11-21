package com.projectplanner.backup;

import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.support.Api;
import com.projectplanner.support.Api.Account;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BackupWatchTest {

    /** Where the test profile points BACKUP_STATUS_FILE. */
    private static final Path STATUS = Path.of("target/test-backup/.backup-status");
    private static final long DAY = 86400;

    @Inject
    BackupWatch watch;

    @AfterEach
    void removeStatus() throws IOException {
        Files.deleteIfExists(STATUS);
    }

    private static void status(String outcome, long goodAgo) throws IOException {
        long now = Instant.now().getEpochSecond();
        Files.createDirectories(STATUS.getParent());
        Files.writeString(STATUS, "last_run_at=" + now + "\nlast_outcome=" + outcome + "\nlast_good=planner-x.dump\nlast_good_at="
                + (now - goodAgo) + "\ninterval=" + DAY + "\n");
    }

    private static long alerts(Account who) {
        return QuarkusTransaction.requiringNew().call(() -> Notification.count("userId = ?1 and type = ?2", who.userId(),
                NotificationType.BACKUP_ALERT));
    }

    @Test
    void ownersSeeTheStatusAndNobodyElse() throws IOException {
        Account owner = Api.signup("Bea Backup");
        Account manager = Api.signup("Mo Manager");
        Api.addMember(owner, manager, "MANAGER");
        String path = "/api/orgs/" + owner.orgId() + "/backup-status";

        owner.as().get(path).then().statusCode(200).body("watched", equalTo(true)).body("state", nullValue());
        status("ok", 3600);
        owner.as().get(path).then().statusCode(200).body("state", equalTo("OK")).body("lastGood", equalTo("planner-x.dump"))
                .body("lastOutcome", equalTo("ok"));
        manager.as().get(path).then().statusCode(403);
        Api.signup("Stranger").as().get(path).then().statusCode(404);
    }

    @Test
    void ownersAreToldOnceWhenTheBackupsStop() throws IOException {
        // Everybody who signs up owns an organisation of their own, so every account here is an owner.
        Account owner = Api.signup("Otto Owner");

        status("ok", 3600);
        watch.check();
        assertThat(alerts(owner)).isZero();

        status("dump_failed", 3 * DAY);
        watch.check();
        watch.check();
        assertThat(alerts(owner)).isEqualTo(1);
        owner.as().get("/api/notifications").then().statusCode(200).body("[0].type", equalTo("BACKUP_ALERT"));

        // Told four days ago; since then a good dump was made two days and an hour ago, and
        // the backups are late again: that is news, once more.
        QuarkusTransaction.requiringNew().run(() -> Notification.update("createdAt = ?1 where userId = ?2 and type = ?3",
                Instant.now().minusSeconds(4 * DAY), owner.userId(), NotificationType.BACKUP_ALERT));
        status("dump_failed", 2 * DAY + 3600);
        watch.check();
        assertThat(alerts(owner)).isEqualTo(2);
    }

    @Test
    void nothingWithoutAStatusFileOrBeforeTheFirstDump() throws IOException {
        Account owner = Api.signup("Nora New");
        watch.check();
        Files.createDirectories(STATUS.getParent());
        Files.writeString(STATUS, "not a status file");
        watch.check();
        assertThat(alerts(owner)).isZero();
    }
}
