package com.projectplanner.schedule;

import java.time.LocalDate;

/** The part of a task the scheduler cares about. Either date may be missing. */
public record ScheduledTask(long id, LocalDate start, LocalDate due) {

    public boolean isScheduled() {
        return start != null && due != null;
    }
}
