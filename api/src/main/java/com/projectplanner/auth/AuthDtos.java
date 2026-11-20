package com.projectplanner.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
            @Size(max = 120) String organisationName,
            @Schema(description = "The token from an invitation link. The email has to be the invited address. Joins that organisation with the role of the invitation.")
            @Size(max = 100) String invitationToken) {
    }

    public record ForgotRequest(@NotBlank @Email @Size(max = 320) String email) {
    }

    public record ResetRequest(
            @Schema(description = "The token from the link in the mail: the part after #token=.") @NotBlank @Size(max = 100) String token,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password) {
    }

    public record AuthOptions(
            @Schema(description = "Whether the server sends mail. Without it there are no reset links and invitations are not mailed.") boolean mail,
            @Schema(description = "Whether joining an organisation at sign-up needs the link from the invitation mail. Without it, signing up with an invited address is enough.") boolean inviteLinkRequired) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record ProfileRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record PasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String newPassword) {
    }

    public record UserResponse(long id, String email, String name, String handle) {
        public static UserResponse of(User user) {
            return new UserResponse(user.id, user.email, user.name, user.handle());
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
    }
}
