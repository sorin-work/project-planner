package com.projectplanner.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.org.OrgAccess;
import com.projectplanner.org.Role;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Path("/api/orgs/{orgId}/backup-status")
@Authenticated
@Tag(name = "Organisation")
public class BackupResource {

    private final OrgAccess access;
    private final BackupWatch watch;
    private final Clock clock;

    public BackupResource(OrgAccess access, BackupWatch watch, Clock clock) {
        this.access = access;
        this.watch = watch;
        this.clock = clock;
    }

    @GET
    @Operation(summary = "How the backups of this server are doing",
            description = "For owners. From the status file of the backup service, when the server is set up to read it"
                    + " (BACKUP_STATUS_FILE). The backups are of the whole server, and only the owners of its first organisation"
                    + " see them: watched is false in every other one, and when the server does not read them at all; without a status file yet there is no state. lastGoodAt is the time in the name of"
                    + " the newest good dump, and lastOutcome is ok, dump_failed or copy_failed.")
    public BackupStatusResponse get(@PathParam("orgId") long orgId) {
        access.require(orgId, Role.OWNER);
        // Only the owners of the server's first organisation look after its backups.
        if (!watch.enabled() || !Long.valueOf(orgId).equals(BackupWatch.serverOrganisationId())) {
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
    /**
     * {@code watched} is whether this server reads the status of its backups at all, {@code state}
     * is missing while there is no status file to read, {@code lastGoodAt} is the time in the name
     * of the newest good dump, and {@code lastOutcome} says how the last run went: ok,
     * dump_failed or copy_failed.
     */
    public record BackupStatusResponse(boolean watched, BackupState state, String lastGood, Instant lastGoodAt,
                                       Instant lastRunAt, String lastOutcome) {
    }
}
