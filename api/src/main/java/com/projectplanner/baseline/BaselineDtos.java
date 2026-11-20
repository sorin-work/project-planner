package com.projectplanner.baseline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.schedule.BaselineComparison;
import com.projectplanner.schedule.BaselineComparison.Change;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BaselineDtos {

    private BaselineDtos() {
    }

    /**
     * @param reference make this the baseline the project is compared against. Left out, the
     *                  first baseline of a project becomes it and later ones do not.
     */
    public record BaselineRequest(@NotBlank @Size(max = 120) String name, Boolean reference) {
    }

    /** Rename a baseline, make it the reference, or both. Its dates cannot be changed. */
    public record UpdateBaselineRequest(
            @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String name, Boolean reference) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BaselineResponse(long id, long projectId, String name, boolean reference, LocalDate projectStart,
                                   LocalDate projectEnd, Long createdBy, String createdByName, Instant createdAt, long tasks) {
    }

    /** One task then and now. Slips are in working days, later being positive. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskChangeResponse(long taskId, String title, boolean milestone, Change change, boolean existedThen,
                                     boolean existsNow, String phaseName, LocalDate baselineStart, LocalDate baselineDue,
                                     LocalDate currentStart, LocalDate currentDue, Integer startSlipDays, Integer finishSlipDays) {
    }

    /** One phase then and now, compared like a task. Slips are in working days, later being positive. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhaseChangeResponse(long phaseId, String name, Change change, boolean existedThen, boolean existsNow,
                                      LocalDate baselineStart, LocalDate baselineEnd, LocalDate currentStart, LocalDate currentEnd,
                                      Integer startSlipDays, Integer finishSlipDays) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DateChangeResponse(LocalDate baseline, LocalDate current, Integer slipDays, Change change) {
        static DateChangeResponse of(BaselineComparison.DateChange change) {
            return new DateChangeResponse(change.baseline(), change.current(), change.slipDays(), change.change());
        }
    }

    public record Counts(int unchanged, int slipped, int pulledIn, int added, int removed) {
    }

    /**
     * @param projectEnd the project's own end date, then and now
     * @param planEnd    the latest due date of any task, then and now
     */
    public record ComparisonResponse(BaselineResponse baseline, List<TaskChangeResponse> tasks, List<PhaseChangeResponse> phases,
                                     DateChangeResponse projectEnd,
                                     DateChangeResponse planEnd, Counts counts, double baselineEstimateHours,
                                     double currentEstimateHours) {
    }
}
