package com.projectplanner.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
}
