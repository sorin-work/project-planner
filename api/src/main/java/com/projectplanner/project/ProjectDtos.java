package com.projectplanner.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.common.ApiDocs;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
    @Schema(description = "A field without a value is left out.")
    public record ProjectResponse(long id, long organisationId, Long clientId, String clientName, String name,
                                  String code, String description, ProjectStatus status, LocalDate startDate,
                                  LocalDate endDate, BigDecimal budgetHours,
                                  @Schema(description = ApiDocs.MANAGERS_ONLY) BigDecimal budgetAmount,
                                  @Schema(description = "A fixed rate for all time on this project. " + ApiDocs.MANAGERS_ONLY) BigDecimal hourlyRate,
                                  String color, Instant createdAt, Instant updatedAt) {
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
