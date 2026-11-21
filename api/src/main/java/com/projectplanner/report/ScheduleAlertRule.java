package com.projectplanner.report;

import java.time.LocalDate;

/**
 * When a project's schedule is announced. Two things are looked at: the health going off
 * track, and the forecast end (the latest due date) moving past the project's end date.
 *
 * <p>Off track is announced once. A project on the edge flips between at risk and off
 * track with every logged hour, so only coming back on track (or being done) takes the
 * state down, and a later slip is announced again. A late end is announced once, with the
 * end it had, and taken down when the plan ends by the end date again or the project
 * has no end date.
 */
public final class ScheduleAlertRule {

    private ScheduleAlertRule() {
    }

    /** What is stored for a project, and what to say. */
    public record Step(boolean offTrackAnnounced, LocalDate lateEndAnnounced, boolean announceOffTrack, boolean announceLateEnd) {
    }

    public static Step step(boolean offTrackAnnounced, LocalDate lateEndAnnounced, HealthStatus status,
                            LocalDate forecastEnd, LocalDate endDate) {
        boolean offTrackNow = offTrackAnnounced;
        boolean announceOffTrack = false;
        if (status == HealthStatus.OFF_TRACK && !offTrackAnnounced) {
            offTrackNow = true;
            announceOffTrack = true;
        } else if (status == HealthStatus.ON_TRACK || status == HealthStatus.DONE || status == HealthStatus.NOT_STARTED) {
            offTrackNow = false;
        }
        boolean late = status != HealthStatus.DONE && endDate != null && forecastEnd != null && forecastEnd.isAfter(endDate);
        LocalDate lateNow = late ? (lateEndAnnounced != null ? lateEndAnnounced : forecastEnd) : null;
        boolean announceLateEnd = late && lateEndAnnounced == null;
        return new Step(offTrackNow, lateNow, announceOffTrack, announceLateEnd);
    }
}
