package com.projectplanner.report;

import com.projectplanner.report.ScheduleAlertRule.Step;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleAlertRuleTest {

    private static final LocalDate END = LocalDate.of(2026, 3, 31);

    @Test
    void offTrackIsAnnouncedOnceAndOnlyBackOnTrackTakesItDown() {
        Step first = ScheduleAlertRule.step(false, null, HealthStatus.OFF_TRACK, null, END);
        assertThat(first.announceOffTrack()).isTrue();
        assertThat(first.offTrackAnnounced()).isTrue();

        // On the edge: at risk and off track again, with nothing new to say.
        Step atRisk = ScheduleAlertRule.step(true, null, HealthStatus.AT_RISK, null, END);
        assertThat(atRisk.offTrackAnnounced()).isTrue();
        assertThat(ScheduleAlertRule.step(true, null, HealthStatus.OFF_TRACK, null, END).announceOffTrack()).isFalse();

        Step back = ScheduleAlertRule.step(true, null, HealthStatus.ON_TRACK, null, END);
        assertThat(back.offTrackAnnounced()).isFalse();
        assertThat(ScheduleAlertRule.step(false, null, HealthStatus.OFF_TRACK, null, END).announceOffTrack()).isTrue();
        assertThat(ScheduleAlertRule.step(true, null, HealthStatus.DONE, null, END).offTrackAnnounced()).isFalse();
    }

    @Test
    void aLateEndIsAnnouncedOnceUntilThePlanEndsInTimeAgain() {
        Step late = ScheduleAlertRule.step(false, null, HealthStatus.ON_TRACK, END.plusDays(3), END);
        assertThat(late.announceLateEnd()).isTrue();
        assertThat(late.lateEndAnnounced()).isEqualTo(END.plusDays(3));

        Step later = ScheduleAlertRule.step(false, END.plusDays(3), HealthStatus.ON_TRACK, END.plusDays(9), END);
        assertThat(later.announceLateEnd()).isFalse();
        assertThat(later.lateEndAnnounced()).isEqualTo(END.plusDays(3));

        Step inTime = ScheduleAlertRule.step(false, END.plusDays(3), HealthStatus.ON_TRACK, END, END);
        assertThat(inTime.lateEndAnnounced()).isNull();
        assertThat(inTime.announceLateEnd()).isFalse();

        // No end date, no due date or a project that is done: nothing is late.
        assertThat(ScheduleAlertRule.step(false, null, HealthStatus.ON_TRACK, END.plusDays(3), null).announceLateEnd()).isFalse();
        assertThat(ScheduleAlertRule.step(false, null, HealthStatus.ON_TRACK, null, END).announceLateEnd()).isFalse();
        assertThat(ScheduleAlertRule.step(false, END.plusDays(3), HealthStatus.DONE, END.plusDays(3), END).lateEndAnnounced()).isNull();
    }
}
