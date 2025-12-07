package com.projectplanner.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    public record UpdateTaskRequest(
            @Size(min = 1, max = 200) String title,
            @Size(max = 10000) String description,
            Long phaseId,
            boolean clearPhase,
            TaskStatus status,
            Priority priority,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal estimateHours,
            LocalDate startDate,
            LocalDate dueDate,
            boolean clearDates,
            Long assigneeId,
            boolean clearAssignee,
            Boolean milestone) {
    }

    public record DependencyRequest(@NotNull Long predecessorId, @Min(0) @Max(365) int lagDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskResponse(long id, long projectId, Long phaseId, Long parentId, String title, String description,
                               TaskStatus status, Priority priority, BigDecimal estimateHours, LocalDate startDate,
                               LocalDate dueDate, Long assigneeId, String assigneeName, boolean milestone,
                               Instant completedAt, Instant createdAt, Instant updatedAt,
                               List<DependencyResponse> predecessors, int subtaskCount) {
    }

    public record DependencyResponse(long id, long predecessorId, long successorId, int lagDays) {
        public static DependencyResponse of(TaskDependency d) {
            return new DependencyResponse(d.id, d.predecessorId, d.successorId, d.lagDays);
        }
    }
}
