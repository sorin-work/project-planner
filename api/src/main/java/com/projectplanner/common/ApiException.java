package com.projectplanner.common;

import java.time.Duration;

/**
 * A business error with an HTTP status and a stable machine-readable code.
 * Mapped to an {@link ApiError} body by {@link ErrorMappers}.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private Duration retryAfter;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** How long the client should wait before it tries again, sent as {@code Retry-After}. Null when it says nothing. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Sends {@code Retry-After} with the answer, in whole seconds, rounded up. */
    public ApiException retryAfter(Duration wait) {
        this.retryAfter = wait;
        return this;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "unauthorized", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "forbidden", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
