package com.projectplanner.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskResponse(long id, long projectId, Long phaseId, Long parentId, String title, String description,
                               TaskStatus status, Priority priority, BigDecimal estimateHours, LocalDate startDate,
                               LocalDate dueDate, Long assigneeId, String assigneeName, boolean milestone,
                               Instant completedAt, Instant createdAt, Instant updatedAt,
                               int subtaskCount) {
    }
}
