package com.projectplanner.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record ProjectRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,15}", message = "use up to 16 letters, digits or dashes") String code,
            @Size(max = 4000) String description,
            Long clientId,
            ProjectStatus status,
            LocalDate startDate,
            LocalDate endDate,
            @DecimalMin("0") @DecimalMax("1000000") BigDecimal budgetHours,
            @DecimalMin("0") @DecimalMax("100000000") BigDecimal budgetAmount,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal hourlyRate,
            @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "must be a hex colour like #4f6bed") String color) {
    }

    /** Money fields are left out for members and client viewers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectResponse(long id, long organisationId, Long clientId, String clientName, String name,
                                  String code, String description, ProjectStatus status, LocalDate startDate,
                                  LocalDate endDate, BigDecimal budgetHours, BigDecimal budgetAmount,
                                  BigDecimal hourlyRate, String color, Instant createdAt, Instant updatedAt) {
    }

    /**
     * A copy of a project's plan. Only what the copy needs to be told is asked for:
     * everything else comes from the source.
     *
     * @param clientId      the client of the new project; left out, it is internal, whoever the source belongs to
     * @param startDate     the day the copied plan begins on
     * @param keepAssignees copy who each task is assigned to, as far as they are still on the team
     */
    public record DuplicateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,15}", message = "use up to 16 letters, digits or dashes") String code,
            Long clientId,
            @NotNull LocalDate startDate,
            boolean keepAssignees) {
    }

    /** What to offer before a project is copied: a name, a code that is free, a start date, and how much there is to copy. */
    public record DuplicateDefaults(String name, String code, LocalDate startDate, long phases, long tasks, long dependencies) {
    }

    public record PhaseRequest(
            @NotBlank @Size(max = 120) String name,
            Integer position,
            LocalDate startDate,
            LocalDate endDate) {
    }

    public record PhaseResponse(long id, long projectId, String name, int position, LocalDate startDate, LocalDate endDate) {
        public static PhaseResponse of(Phase phase) {
            return new PhaseResponse(phase.id, phase.projectId, phase.name, phase.position, phase.startDate, phase.endDate);
        }
    }
}
