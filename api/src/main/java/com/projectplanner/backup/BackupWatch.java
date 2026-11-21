package com.projectplanner.backup;

import com.projectplanner.mail.MailOutbox;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.mail.Mails;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the status file of the backup service, from a read-only mount, when BACKUP_STATUS_FILE
 * names it. The backups are of the whole server, and they are the business of whoever runs it:
 * the addresses in BACKUP_ALERT_EMAILS. Only accounts with one of those addresses see the
 * status in the app, and those addresses are mailed when it goes bad, whether or not they
 * have an account.
 * <p>
 * Every five minutes, when the backups are bad, the operators are mailed once for each change
 * of the state or of the dump it is about: a copy that failed and, later, backups that
 * stopped are two mails, and the same state of the same dump is never a second one. The
 * marker of what was mailed is a row of {@code backup_alert_marks}, whose unique key lets one
 * instance of the api send it.
 */
@ApplicationScoped
public class BackupWatch {

    private static final Logger LOG = Logger.getLogger(BackupWatch.class);
    /** Larger than any status file the backup service writes. */
    private static final long MAX_BYTES = 4096;
    private static final String KIND = "backup alert";

    private final Optional<String> statusFile;
    private final List<String> operators;
    private final MailSettings mail;
    private final Mails mails;
    private final MailOutbox outbox;
    private final Clock clock;

    public BackupWatch(@ConfigProperty(name = "planner.backup.status-file") Optional<String> statusFile,
                       @ConfigProperty(name = "planner.backup.alert-emails") Optional<String> operators,
                       MailSettings mail, Mails mails, MailOutbox outbox, Clock clock) {
        this.statusFile = statusFile.filter(path -> !path.isBlank());
        this.operators = operators.map(BackupWatch::addresses).orElse(List.of());
        this.mail = mail;
        this.mails = mails;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** The addresses of a comma or space separated list, in lower case. */
    static List<String> addresses(String list) {
        return Arrays.stream(list.split("[,\\s]+")).map(String::trim).filter(a -> a.contains("@"))
                .map(a -> a.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    public boolean enabled() {
        return statusFile.isPresent();
    }

    /** Whether the account with this address looks after the backups. */
    public boolean isOperator(String email) {
        return email != null && operators.contains(email.toLowerCase(Locale.ROOT));
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

    /** Mails the operators when the backups are bad in a way they have not been mailed about. Answers whether it did. */
    public boolean check() {
        Optional<BackupStatus> status = read();
        if (status.isEmpty() || operators.isEmpty()) {
            return false;
        }
        BackupState state = status.get().state(clock.instant());
        if (!BackupStatus.bad(state)) {
            return false;
        }
        String marker = state + ":" + (status.get().lastGood() == null ? "" : status.get().lastGood());
        if (!mark(marker)) {
            return false;
        }
        String text = switch (state) {
            case LATE -> "The backups of this server have stopped: the newest good dump is more than twice the interval old.";
            case COPY_FAILED -> "The newest backup of this server could not be copied off the machine.";
            case FUTURE_DATED -> "The newest backup of this server is dated in the future, so how old the backups are cannot be told."
                    + " Check the clock of the machine.";
            default -> "The backups of this server need a look.";
        };
        if (!mail.enabled()) {
            LOG.warnf("Backups are %s, and this server sends no mail to tell the operators", state);
            return true;
        }
        int sent = 0;
        for (String to : operators) {
            if (outbox.deliver(KIND, mails.backupAlert(to, text))) {
                sent++;
            }
        }
        LOG.warnf("Backups are %s; mailed %d of %d operators", state, sent, operators.size());
        return true;
    }

    /** Writes the marker, in a transaction of its own. False when it was there already: that was mailed. */
    private boolean mark(String marker) {
        if (QuarkusTransaction.requiringNew().call(() -> BackupAlertMark.count("marker", marker) > 0)) {
            return false;
        }
        try {
            Instant now = clock.instant();
            QuarkusTransaction.requiringNew().run(() -> {
                BackupAlertMark mark = new BackupAlertMark();
                mark.marker = marker.length() > 300 ? marker.substring(0, 300) : marker;
                mark.sentAt = now;
                mark.persist();
            });
            return true;
        } catch (RuntimeException e) {
            // Another instance wrote it first.
            return false;
        }
    }
}
