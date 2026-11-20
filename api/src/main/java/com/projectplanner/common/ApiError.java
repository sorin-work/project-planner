package com.projectplanner.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** The JSON body of every error response. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "The body of every error response.")
public record ApiError(
        @Schema(description = "The HTTP status, once more.", required = true) int status,
        @Schema(description = "Says which error it is. Stable, so this is what a client should look at.", required = true) String code,
        @Schema(description = "For people, in English. The wording may change.", required = true) String message,
        @Schema(description = "Only with validation_failed: one entry for every field that was refused.") List<FieldError> errors) {

    public ApiError(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}
