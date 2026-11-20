package com.projectplanner.auth;

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
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

@Path("/api/me")
@Authenticated
@Tag(name = "Auth")
public class MeResource {

    private final CurrentUser currentUser;
    private final PasswordHasher hasher;

    public MeResource(CurrentUser currentUser, PasswordHasher hasher) {
        this.currentUser = currentUser;
        this.hasher = hasher;
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
    @ResponseStatus(204)
    @Operation(summary = "Change your password")
    @Fails(status = 400, code = "wrong_password", when = "currentPassword is not your password")
    public void changePassword(@Valid @NotNull PasswordRequest request) {
        User user = currentUser.get();
        if (!hasher.matches(request.currentPassword(), user.passwordHash)) {
            throw ApiException.badRequest("wrong_password", "Current password is incorrect");
        }
        user.passwordHash = hasher.hash(request.newPassword());
    }

    public record MeResponse(UserResponse user, List<MembershipSummary> organisations) {
    }
}
