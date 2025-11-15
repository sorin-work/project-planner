package com.projectplanner.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The JSON body of every error response. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(int status, String code, String message, List<FieldError> errors) {

    public ApiError(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public record FieldError(String field, String message) {
    }
}
