package com.projectplanner.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.common.ApiDocs;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
                                  String color, Instant createdAt, Instant updatedAt,
                                  @Schema(description = "Only in the answer to a copy with copyAttachments: the files that were copied.") Integer attachmentsCopied,
                                  @Schema(description = "Only in the answer to a copy with copyAttachments: the files that were left out because "
                                          + "their file was missing on the server.") Integer attachmentsSkipped) {

        public ProjectResponse withCopiedFiles(int copied, int skipped) {
            return new ProjectResponse(id, organisationId, clientId, clientName, name, code, description, status, startDate, endDate,
                    budgetHours, budgetAmount, hourlyRate, color, createdAt, updatedAt, copied, skipped);
        }
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
            boolean keepAssignees,
            @Schema(description = "Copy the files attached to the tasks as well. Left out, they are not copied.") boolean copyAttachments) {

        public DuplicateRequest(String name, String code, Long clientId, LocalDate startDate, boolean keepAssignees) {
            this(name, code, clientId, startDate, keepAssignees, false);
        }
    }

    /** What to offer before a project is copied: a name, a code that is free, a start date, and how much there is to copy. */
    /** {@code attachments} and {@code attachmentBytes}: the files on the tasks, which are only copied when asked for. */
    public record DuplicateDefaults(String name, String code, LocalDate startDate, long phases, long tasks, long dependencies,
                                    long attachments, long attachmentBytes) {
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
