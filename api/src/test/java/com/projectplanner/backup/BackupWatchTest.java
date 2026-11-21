package com.projectplanner.backup;

import com.projectplanner.auth.TokenService;
import com.projectplanner.auth.User;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
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

    @Inject
    TokenService tokens;

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

    private static long alerts(long userId) {
        return QuarkusTransaction.requiringNew().call(() -> Notification.count("userId = ?1 and type = ?2", userId,
                NotificationType.BACKUP_ALERT));
    }

    /**
     * An owner of the server's first organisation, the one whose owners look after the backups.
     * Whichever test made it, its owner is signed in here with a token of their own.
     */
    private Account serverOwner() {
        Api.signup("Somebody");
        return QuarkusTransaction.requiringNew().call(() -> {
            long org = BackupWatch.serverOrganisationId();
            Membership owner = Membership.<Membership>find("organisationId = ?1 and role = ?2 order by id", org, Role.OWNER)
                    .firstResult();
            User user = User.findById(owner.userId);
            return new Account(tokens.issue(user).value(), user.id, user.email, user.handle(), org);
        });
    }

    @Test
    void theOwnersOfTheFirstOrganisationSeeTheStatusAndNobodyElse() throws IOException {
        Account owner = serverOwner();
        Account other = Api.signup("Olga Other");
        Account manager = Api.signup("Mo Manager");
        Api.addMember(owner, manager, "MANAGER");
        String path = "/api/orgs/" + owner.orgId() + "/backup-status";

        owner.as().get(path).then().statusCode(200).body("watched", equalTo(true)).body("state", nullValue());
        status("ok", 3600);
        owner.as().get(path).then().statusCode(200).body("state", equalTo("OK")).body("lastGood", equalTo("planner-x.dump"))
                .body("lastOutcome", equalTo("ok"));
        manager.as().get(path).then().statusCode(403);
        other.as().get(path).then().statusCode(404);
        // An owner of any other organisation: not theirs to look after.
        other.as().get("/api/orgs/" + other.orgId() + "/backup-status").then().statusCode(200)
                .body("watched", equalTo(false)).body("state", nullValue());
    }

    @Test
    void theyAreToldOnceWhenTheBackupsStop() throws IOException {
        Account owner = serverOwner();
        // Everybody who signs up owns an organisation of their own: that is not the server's.
        Account other = Api.signup("Otto Other");
        // Whatever they were told before is from long ago.
        QuarkusTransaction.requiringNew().run(() -> Notification.update("createdAt = ?1 where userId = ?2 and type = ?3",
                Instant.now().minusSeconds(10 * DAY), owner.userId(), NotificationType.BACKUP_ALERT));
        long before = alerts(owner.userId());

        status("ok", 3600);
        watch.check();
        assertThat(alerts(owner.userId())).isEqualTo(before);

        status("dump_failed", 3 * DAY);
        watch.check();
        watch.check();
        assertThat(alerts(owner.userId())).isEqualTo(before + 1);
        assertThat(alerts(other.userId())).isZero();

        // Told four days ago; since then a good dump was made two days and an hour ago, and
        // the backups are late again: that is news, once more.
        QuarkusTransaction.requiringNew().run(() -> Notification.update("createdAt = ?1 where userId = ?2 and type = ?3",
                Instant.now().minusSeconds(4 * DAY), owner.userId(), NotificationType.BACKUP_ALERT));
        status("dump_failed", 2 * DAY + 3600);
        watch.check();
        assertThat(alerts(owner.userId())).isEqualTo(before + 2);
    }

    @Test
    void nothingWithoutAStatusFileOrBeforeTheFirstDump() throws IOException {
        Account owner = serverOwner();
        long before = alerts(owner.userId());
        watch.check();
        Files.createDirectories(STATUS.getParent());
        Files.writeString(STATUS, "not a status file");
        watch.check();
        status("dump_failed", 0);
        Files.writeString(STATUS, Files.readString(STATUS).replace("last_good=planner-x.dump", "last_good=")
                .replaceAll("last_good_at=\\d+", "last_good_at="));
        watch.check();
        assertThat(alerts(owner.userId())).isEqualTo(before);
    }
}
