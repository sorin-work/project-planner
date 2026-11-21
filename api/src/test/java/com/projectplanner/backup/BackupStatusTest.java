package com.projectplanner.backup;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupStatusTest {

    private static final long DAY = 86400;
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private static String file(String outcome, String good, Long goodAt) {
        return "last_run_at=" + NOW.getEpochSecond() + "\nlast_outcome=" + outcome + "\nlast_good=" + (good == null ? "" : good)
                + "\nlast_good_at=" + (goodAt == null ? "" : goodAt) + "\ninterval=" + DAY + "\n";
    }

    @Test
    void readsWhatTheBackupServiceWrites() {
        BackupStatus status = BackupStatus.parse(file("ok", "planner-20260310T030000Z.dump.age", NOW.getEpochSecond() - 9 * 3600)).orElseThrow();
        assertThat(status.lastGood()).isEqualTo("planner-20260310T030000Z.dump.age");
        assertThat(status.lastGoodAt()).isEqualTo(NOW.minusSeconds(9 * 3600));
        assertThat(status.interval()).isEqualTo(DAY);
        assertThat(status.state(NOW)).isEqualTo(BackupState.OK);

        assertThat(BackupStatus.parse("")).isEmpty();
        assertThat(BackupStatus.parse("hello")).isEmpty();
        assertThat(BackupStatus.parse(file("ok", null, null).replace("interval=86400", "interval=-1"))).isEmpty();
    }

    @Test
    void lateAfterTwiceTheIntervalByTheTimeInTheName() {
        long goodAt = NOW.getEpochSecond() - 2 * DAY - 1;
        assertThat(BackupStatus.parse(file("dump_failed", "x.dump", goodAt)).orElseThrow().state(NOW)).isEqualTo(BackupState.LATE);
        // A dump failed an hour ago, but the last good one is recent: it is tried again, nobody is told yet.
        BackupStatus failing = BackupStatus.parse(file("dump_failed", "x.dump", NOW.getEpochSecond() - DAY)).orElseThrow();
        assertThat(failing.state(NOW)).isEqualTo(BackupState.OK);
        assertThat(BackupStatus.bad(failing.state(NOW))).isFalse();
        // A dump dated ahead of the clock has an age nobody can tell: bad until a correctly dated one is made.
        BackupStatus ahead = BackupStatus.parse(file("ok", "x.dump", NOW.getEpochSecond() + 5 * DAY)).orElseThrow();
        assertThat(ahead.state(NOW)).isEqualTo(BackupState.FUTURE_DATED);
        assertThat(BackupStatus.bad(BackupState.FUTURE_DATED)).isTrue();
    }

    @Test
    void aCopyThatFailedIsBadAndNoDumpYetIsNot() {
        BackupStatus copy = BackupStatus.parse(file("copy_failed", "x.dump", NOW.getEpochSecond() - 60)).orElseThrow();
        assertThat(copy.state(NOW)).isEqualTo(BackupState.COPY_FAILED);
        assertThat(BackupStatus.bad(BackupState.COPY_FAILED)).isTrue();
        BackupStatus fresh = BackupStatus.parse(file("dump_failed", null, null)).orElseThrow();
        assertThat(fresh.state(NOW)).isEqualTo(BackupState.NO_GOOD_DUMP);
        assertThat(BackupStatus.bad(BackupState.NO_GOOD_DUMP)).isFalse();
    }
}
