package com.projectplanner.time;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
                                    boolean running, boolean locked) {
    }

    public record DayTotal(LocalDate date, int minutes) {
    }

    public record WeekResponse(Long timesheetId, long userId, String userName, LocalDate weekStart,
                               TimesheetStatus status, Instant submittedAt, Instant reviewedAt,
                               String reviewedByName, String reviewNote, List<TimeEntryResponse> entries,
                               List<DayTotal> days, int totalMinutes, int billableMinutes) {
    }

    public record SubmitRequest(@NotNull LocalDate weekStart) {
    }

    public record ReviewRequest(@Size(max = 1000) String note) {
    }

    public record TimesheetSummary(long id, long userId, String userName, LocalDate weekStart, TimesheetStatus status,
                                   Instant submittedAt, int totalMinutes, int billableMinutes, BigDecimal billableAmount) {
    }
}
