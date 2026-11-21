package com.projectplanner.backup;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the status file of the backup service says (scripts/backup.sh writes it after every
 * run), and what that means. The time of the last good dump is the time in its name, as
 * everywhere in the backups: never a modification time, which on a share comes from another
 * machine's clock.
 *
 * @param lastRunAt   when the last run ended, by the clock of the backup service
 * @param lastOutcome ok, dump_failed or copy_failed
 * @param lastGood    the name of the newest good dump, or null before the first one
 * @param lastGoodAt  the time in that name
 * @param interval    the seconds from one dump to the next
 */
public record BackupStatus(Instant lastRunAt, String lastOutcome, String lastGood, Instant lastGoodAt, long interval) {

    /** Reads the file's "key=value" lines. Empty when it is not one the backup service wrote. */
    public static Optional<BackupStatus> parse(String text) {
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        Long interval = number(values.get("interval"));
        Long lastRun = number(values.get("last_run_at"));
        String outcome = values.get("last_outcome");
        if (interval == null || interval <= 0 || lastRun == null || outcome == null || outcome.isEmpty()) {
            return Optional.empty();
        }
        Long goodAt = number(values.get("last_good_at"));
        String good = values.get("last_good");
        boolean hasGood = goodAt != null && good != null && !good.isEmpty();
        return Optional.of(new BackupStatus(Instant.ofEpochSecond(lastRun), outcome, hasGood ? good : null,
                hasGood ? Instant.ofEpochSecond(goodAt) : null, interval));
    }

    /** What the status means at {@code now}. A good dump dated ahead of the clock counts as made now. */
    public BackupState state(Instant now) {
        if (lastGoodAt == null) {
            return BackupState.NO_GOOD_DUMP;
        }
        Instant made = lastGoodAt.isAfter(now) ? now : lastGoodAt;
        if (made.plusSeconds(2 * interval).isBefore(now)) {
            return BackupState.LATE;
        }
        return "copy_failed".equals(lastOutcome) ? BackupState.COPY_FAILED : BackupState.OK;
    }

    /** Whether owners are to hear about it. No good dump yet is a new install, not a failure. */
    public static boolean bad(BackupState state) {
        return state == BackupState.LATE || state == BackupState.COPY_FAILED;
    }

    private static Long number(String value) {
        if (value == null || !value.matches("\\d{1,15}")) {
            return null;
        }
        return Long.parseLong(value);
    }
}
