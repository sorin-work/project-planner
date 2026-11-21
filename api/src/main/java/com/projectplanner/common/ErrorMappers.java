package com.projectplanner.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/** Turns exceptions into consistent {@link ApiError} JSON responses. */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response api(ApiException e) {
        Response.ResponseBuilder response = builder(new ApiError(e.status(), e.code(), e.getMessage()));
        if (e.retryAfter() != null) {
            response.header("Retry-After", seconds(e.retryAfter()));
        }
        return response.build();
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        List<ApiError.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(fieldName(v), v.getMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field))
                .toList();
        return respond(new ApiError(400, "validation_failed", "Some fields are invalid", errors));
    }

    @ServerExceptionMapper
    public Response badJson(JsonProcessingException e) {
        return respond(new ApiError(400, "malformed_json", "The request body is not valid JSON"));
    }

    @ServerExceptionMapper
    public Response unauthorized(UnauthorizedException e) {
        return respond(new ApiError(401, "unauthorized", "Sign in to continue"));
    }

    @ServerExceptionMapper
    public Response authFailed(AuthenticationFailedException e) {
        return respond(new ApiError(401, "unauthorized", "Your session has expired, sign in again"));
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException e) {
        return respond(new ApiError(403, "forbidden", "You are not allowed to do that"));
    }

    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        String code = switch (status) {
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 415 -> "unsupported_media_type";
            default -> "http_" + status;
        };
        String message = status == 404 ? "Not found" : e.getMessage();
        return respond(new ApiError(status, code, message));
    }

    @ServerExceptionMapper
    public Response unexpected(RuntimeException e) {
        LOG.error("Unhandled error", e);
        return respond(new ApiError(500, "internal_error", "Something went wrong on our side"));
    }

    private static String fieldName(ConstraintViolation<?> violation) {
        // Paths look like "create.request.email"; the client only cares about the last part.
        String path = violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private static Response respond(ApiError error) {
        return builder(error).build();
    }

    private static Response.ResponseBuilder builder(ApiError error) {
        return Response.status(error.status()).type(MediaType.APPLICATION_JSON).entity(error);
    }

    /** Whole seconds, rounded up and at least one, so that a client that waits that long is let through. */
    static long seconds(Duration wait) {
        long millis = Math.max(wait.toMillis(), 1);
        return (millis + 999) / 1000;
    }
}
