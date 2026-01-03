package com.projectplanner.time;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class TimeDtos {

    private TimeDtos() {
    }

    public record TimeEntryRequest(
            @NotNull Long projectId,
            Long taskId,
            @NotNull LocalDate date,
            @NotNull @Min(value = 1, message = "must be at least one minute") @Max(value = 1440, message = "can't be more than 24 hours") Integer minutes,
            @Size(max = 1000) String description,
            Boolean billable) {
    }

    public record StartTimerRequest(@NotNull Long projectId, Long taskId, @Size(max = 1000) String description, Boolean billable) {
    }

    public record TimeEntryResponse(long id, long userId, String userName, long projectId, String projectName,
                                    String projectCode, Long taskId, String taskTitle, LocalDate date, int minutes,
                                    String description, boolean billable, Instant startedAt, Instant endedAt,
                                    boolean running) {
    }
}
