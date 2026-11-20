package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.PasswordRequest;
import com.projectplanner.auth.AuthDtos.ProfileRequest;
import com.projectplanner.auth.AuthDtos.UserResponse;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgDtos.MembershipSummary;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Clock;
import java.util.List;

@Path("/api/me")
@Authenticated
@Tag(name = "Auth")
public class MeResource {

    private final CurrentUser currentUser;
    private final PasswordHasher hasher;
    private final AuthService auth;
    private final Clock clock;

    public MeResource(CurrentUser currentUser, PasswordHasher hasher, AuthService auth, Clock clock) {
        this.currentUser = currentUser;
        this.hasher = hasher;
        this.auth = auth;
        this.clock = clock;
    }

    @GET
    @Operation(summary = "Your account and the organisations you are in")
    public MeResponse me() {
        User user = currentUser.get();
        List<MembershipSummary> orgs = Membership.forUser(user.id).stream().map(MembershipSummary::of).toList();
        return new MeResponse(UserResponse.of(user), orgs);
    }

    @PATCH
    @Transactional
    @Operation(summary = "Change your name")
    public UserResponse update(@Valid @NotNull ProfileRequest request) {
        User user = currentUser.get();
        user.name = request.name().trim();
        return UserResponse.of(user);
    }

    @POST
    @Path("/password")
    @Transactional
    @Operation(summary = "Change your password",
            description = "Every session that was started before ends, this one too, and reset links that still work stop working. The answer holds a new token to carry on with.")
    @Fails(status = 400, code = "wrong_password", when = "currentPassword is not your password")
    public AuthResponse changePassword(@Valid @NotNull PasswordRequest request) {
        User user = currentUser.get();
        if (!hasher.matches(request.currentPassword(), user.passwordHash)) {
            throw ApiException.badRequest("wrong_password", "Current password is incorrect");
        }
        user.changePassword(hasher.hash(request.newPassword()), clock.instant());
        return auth.respond(user);
    }

    public record MeResponse(UserResponse user, List<MembershipSummary> organisations) {
    }
}
