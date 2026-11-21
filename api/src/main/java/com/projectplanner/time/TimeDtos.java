package com.projectplanner.time;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TimeDtos {

    private TimeDtos() {
    }

    /**
     * {@code clientKey} is optional and only read on a create: a key the client makes up, so
     * that the same create sent twice (after an answer that got lost) makes one entry.
     */
    public record TimeEntryRequest(
            @NotNull Long projectId,
            Long taskId,
            @NotNull LocalDate date,
            @NotNull @Min(value = 1, message = "must be at least one minute") @Max(value = 1440, message = "can't be more than 24 hours") Integer minutes,
            @Size(max = 1000) String description,
            Boolean billable,
            @Schema(description = "Optional, and only read when an entry is created. A key the client makes up, so that a create that is sent twice makes one entry.")
            @Size(min = 8, max = 64) String clientKey) {
    }

    public record StartTimerRequest(@NotNull Long projectId, Long taskId, @Size(max = 1000) String description, Boolean billable) {
    }

    public record TimeEntryResponse(long id, long userId, String userName, long projectId, String projectName,
                                    String projectCode, Long taskId, String taskTitle, LocalDate date, int minutes,
                                    String description, boolean billable, Instant startedAt, Instant endedAt,
                                    boolean running, boolean locked) {
    }

    /** {@code holiday} is the name of the organisation's holiday on that day, left out on other days. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DayTotal(LocalDate date, int minutes, String holiday) {
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
                                   Instant submittedAt, int totalMinutes, int billableMinutes,
                                   @Schema(description = "The billable time at each person's rate.") BigDecimal billableAmount) {
    }

    /** {@code latestWeekStart} is the most recent week that is over, so the newest one that can be asked for. */
    public record MissingResponse(LocalDate weekStart, LocalDate latestWeekStart, List<MissingTimesheets.Missing> people) {
    }

    public record RemindRequest(@NotNull LocalDate weekStart,
                                @NotNull @Size(min = 1, max = 200) List<@NotNull Long> userIds) {
    }

    public record SkippedReminder(long userId, MissingTimesheets.Skip reason) {
    }

    public record RemindResponse(LocalDate weekStart, List<Long> reminded, List<SkippedReminder> skipped) {
    }

    /** A week as it was on screen: its id, and when it was submitted then. */
    public record SheetToApprove(@NotNull Long id, @NotNull Instant submittedAt) {
    }

    public record ApproveRequest(@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid SheetToApprove> sheets) {
    }

    /** Why a week on an approval request was left out. */
    public enum ApprovalSkip {
        /** No such week in this organisation. */
        NOT_FOUND,
        /** A manager's own week, which another manager or an owner reviews. */
        OWN_WEEK,
        /** No longer waiting for review: approved, sent back or withdrawn since. */
        NOT_SUBMITTED,
        /** Withdrawn and submitted again since it was on screen, so it may have changed. */
        CHANGED
    }

    public record SkippedApproval(long timesheetId, ApprovalSkip reason) {
    }

    public record ApproveResponse(List<Long> approved, List<SkippedApproval> skipped) {
    }
}
