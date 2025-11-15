package com.projectplanner.common;

/**
 * A business error with an HTTP status and a stable machine-readable code.
 * Mapped to an {@link ApiError} body by {@link ErrorMappers}.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

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
