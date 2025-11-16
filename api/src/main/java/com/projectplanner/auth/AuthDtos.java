package com.projectplanner.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
            @Size(max = 120) String organisationName) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserResponse(long id, String email, String name, String handle) {
        public static UserResponse of(User user) {
            return new UserResponse(user.id, user.email, user.name, user.handle());
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
    }
}
