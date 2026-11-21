package com.projectplanner.backup;

import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Role;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the status file of the backup service, from a read-only mount, when BACKUP_STATUS_FILE
 * names it. Every five minutes, when the backups have gone bad, every owner gets a
 * BACKUP_ALERT, once: not again while there is no newer good dump than the one there was when
 * they were told. A dump that keeps failing to be copied tells them once per dump.
 */
@ApplicationScoped
public class BackupWatch {

    private static final Logger LOG = Logger.getLogger(BackupWatch.class);
    /** Larger than any status file the backup service writes. */
    private static final long MAX_BYTES = 4096;

    private final Optional<String> statusFile;
    private final Notifier notifier;
    private final Clock clock;

    public BackupWatch(@ConfigProperty(name = "planner.backup.status-file") Optional<String> statusFile, Notifier notifier,
                       Clock clock) {
        this.statusFile = statusFile.filter(path -> !path.isBlank());
        this.notifier = notifier;
        this.clock = clock;
    }

    public boolean enabled() {
        return statusFile.isPresent();
    }

    /** The status as it is now. Empty when the file is not there or is not one the backup service wrote. */
    public Optional<BackupStatus> read() {
        if (statusFile.isEmpty()) {
            return Optional.empty();
        }
        Path path = Path.of(statusFile.get());
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
                return Optional.empty();
            }
            return BackupStatus.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.debugf("Could not read the backup status: %s", e.getMessage());
            return Optional.empty();
        }
    }

    @Scheduled(identity = "backup-watch", every = "5m", delayed = "60s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyFiveMinutes() {
        check();
    }

    /** Tells the owners when the backups are bad and they have not heard of it yet. Answers how many were told. */
    public int check() {
        Optional<BackupStatus> status = read();
        if (status.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        BackupState state = status.get().state(now);
        if (!BackupStatus.bad(state)) {
            return 0;
        }
        Instant since = status.get().lastGoodAt();
        return QuarkusTransaction.requiringNew().call(() -> tell(state, since));
    }

    private int tell(BackupState state, Instant since) {
        // The owners' memberships, locked in id order: two instances of the api that look at the
        // same moment wait for each other here, and the second finds the alerts of the first.
        List<Membership> owners = Membership.<Membership>find("role = ?1 order by id", Role.OWNER)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list();
        Map<Long, Long> organisationOf = new LinkedHashMap<>();
        owners.forEach(m -> organisationOf.putIfAbsent(m.userId, m.organisationId));
        if (organisationOf.isEmpty()) {
            return 0;
        }
        List<Long> told = Notification.<Notification>list("userId in ?1 and type = ?2 and createdAt >= ?3",
                        organisationOf.keySet(), NotificationType.BACKUP_ALERT, since).stream()
                .map(n -> n.userId).distinct().toList();
        String message = state == BackupState.LATE
                ? "The backups of this server have stopped: the newest good dump is more than two intervals old"
                : "The newest backup of this server could not be copied off the machine";
        List<Long> telling = new ArrayList<>();
        organisationOf.forEach((userId, organisationId) -> {
            if (!told.contains(userId)) {
                telling.add(userId);
                notifier.notify(userId, organisationId, null, NotificationType.BACKUP_ALERT, message, null, null);
            }
        });
        if (!telling.isEmpty()) {
            LOG.warnf("Backups are %s; told %d owners", state, telling.size());
        }
        return telling.size();
    }
}
