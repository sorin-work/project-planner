package com.projectplanner.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.auth.CurrentUser;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Path("/api/backup-status")
@Authenticated
@Tag(name = "Organisation")
public class BackupResource {

    private final CurrentUser currentUser;
    private final BackupWatch watch;
    private final Clock clock;

    public BackupResource(CurrentUser currentUser, BackupWatch watch, Clock clock) {
        this.currentUser = currentUser;
        this.watch = watch;
        this.clock = clock;
    }

    @GET
    @Operation(summary = "How the backups of this server are doing",
            description = "For the operators of the server, the accounts whose address is in BACKUP_ALERT_EMAILS, and only when"
                    + " the server reads the status file of the backup service (BACKUP_STATUS_FILE). For everybody else watched"
                    + " is false and nothing else is said. Without a status file yet there is no state. lastGoodAt is the time"
                    + " in the name of the newest good dump, and lastOutcome is ok, dump_failed or copy_failed.")
    public BackupStatusResponse get() {
        if (!watch.enabled() || !watch.isOperator(currentUser.get().email)) {
            return new BackupStatusResponse(false, null, null, null, null, null);
        }
        Optional<BackupStatus> status = watch.read();
        if (status.isEmpty()) {
            return new BackupStatusResponse(true, null, null, null, null, null);
        }
        BackupStatus s = status.get();
        return new BackupStatusResponse(true, s.state(clock.instant()), s.lastGood(), s.lastGoodAt(), s.lastRunAt(), s.lastOutcome());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BackupStatusResponse(boolean watched, BackupState state, String lastGood, Instant lastGoodAt,
                                       Instant lastRunAt, String lastOutcome) {
    }
}
