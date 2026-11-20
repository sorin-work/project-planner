package com.projectplanner.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.common.ApiDocs;
import com.projectplanner.schedule.DependencyType;
import com.projectplanner.schedule.ScheduleCheck;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record CreateTaskRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            Long phaseId,
            Long parentId,
            TaskStatus status,
            Priority priority,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal estimateHours,
            LocalDate startDate,
            LocalDate dueDate,
            Long assigneeId,
            boolean milestone) {
    }

    /**
     * Partial update: only non-null fields change. Use the {@code clear*} flags to
     * remove an assignee, phase or dates.
     */
    @Schema(description = "Only the fields that are sent change. A value cannot be removed by sending null, which is what the clear flags are for.")
    public record UpdateTaskRequest(
            @Size(min = 1, max = 200) String title,
            @Size(max = 10000) String description,
            Long phaseId,
            @Schema(description = ApiDocs.CLEARS) boolean clearPhase,
            TaskStatus status,
            Priority priority,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal estimateHours,
            LocalDate startDate,
            LocalDate dueDate,
            @Schema(description = "Send true to remove both dates. " + "Leaving a field out, or sending null, keeps it.") boolean clearDates,
            Long assigneeId,
            @Schema(description = ApiDocs.CLEARS) boolean clearAssignee,
            Boolean milestone,
            @Schema(description = "The place in its board column, counted from 0.") @Min(0) Integer position) {
    }

    /** Moves a task by working days (negative is earlier), keeping its length in working days. Used by timeline drags. */
    public record ShiftRequest(@NotNull @Min(-3650) @Max(3650) Integer days) {
    }

    /** The most working days a lag or a lead can be. */
    public static final int MAX_LAG_DAYS = 365;

    /**
     * @param lagDays working days after the point the type counts from. Negative is a lead.
     * @param type    FS when left out
     */
    public record DependencyRequest(@NotNull Long predecessorId,
                                    @Schema(description = "Working days. Negative is a lead.") @Min(-MAX_LAG_DAYS) @Max(MAX_LAG_DAYS) int lagDays,
                                    @Schema(description = "FS when left out.") DependencyType type) {

        public DependencyRequest(Long predecessorId, int lagDays) {
            this(predecessorId, lagDays, null);
        }
    }

    /** Changes the type or the lag of a link. A field that is left out stays as it is. */
    public record UpdateDependencyRequest(@Schema(description = "Working days. Negative is a lead.") @Min(-MAX_LAG_DAYS) @Max(MAX_LAG_DAYS) Integer lagDays,
                                          DependencyType type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A field without a value is left out.")
    public record TaskResponse(long id, long projectId, Long phaseId, Long parentId, String title, String description,
                               TaskStatus status, Priority priority, BigDecimal estimateHours, LocalDate startDate,
                               LocalDate dueDate, Long assigneeId, String assigneeName, boolean milestone, int position,
                               Instant completedAt, Instant createdAt, Instant updatedAt,
                               List<DependencyResponse> predecessors, int subtaskCount, double loggedHours) {
    }

    public record DependencyResponse(long id, long predecessorId, long successorId, int lagDays, DependencyType type) {
        public static DependencyResponse of(TaskDependency d) {
            return new DependencyResponse(d.id, d.predecessorId, d.successorId, d.lagDays, d.type);
        }
    }

    /** A change to one task plus every dependant that had to move because of it. */
    public record TaskChange(TaskResponse task,
                             @Schema(description = "The tasks that were pushed back because they depend on this one. Empty when nothing had to move.") List<TaskResponse> rescheduled) {
    }

    /**
     * One problem in the plan, see {@link ScheduleCheck}. The suggested dates are only
     * there when moving the task fixes the issue; send both back in a task update.
     * {@code suggestionCauses} is there when that move would get the task reported for
     * something else.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduleIssueResponse(ScheduleCheck.IssueType type, long taskId, Long relatedTaskId, Integer days,
                                        LocalDate suggestedStart, LocalDate suggestedDue,
                                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ScheduleCheck.IssueType> suggestionCauses) {
        public static ScheduleIssueResponse of(ScheduleCheck.Issue issue) {
            return new ScheduleIssueResponse(issue.type(), issue.taskId(), issue.relatedTaskId(), issue.days(),
                    issue.suggestedStart(), issue.suggestedDue(), issue.suggestionCauses());
        }
    }
}
