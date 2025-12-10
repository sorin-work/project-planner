package com.projectplanner.auth;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import com.projectplanner.auth.AuthDtos.UserResponse;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Invitation;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class AuthService {

    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final ActivityLog activity;
    private final Clock clock;

    public AuthService(PasswordHasher hasher, TokenService tokens, ActivityLog activity, Clock clock) {
        this.hasher = hasher;
        this.tokens = tokens;
        this.activity = activity;
        this.clock = clock;
    }

    /**
     * Creates the account. Pending invitations for the email are accepted straight
     * away; without any, the user gets a new organisation of their own as owner.
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = User.normalizeEmail(request.email());
        if (User.findByEmail(email).isPresent()) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        Instant now = clock.instant();
        User user = new User();
        user.email = email;
        user.name = request.name().trim();
        user.passwordHash = hasher.hash(request.password());
        user.createdAt = now;
        user.persist();

        List<Invitation> invitations = Invitation.pendingFor(email);
        for (Invitation invitation : invitations) {
            invitation.accept(user, now);
            activity.record(invitation.organisationId, null, null, user.id, "member.joined",
                    user.name + " joined as " + invitation.role.label());
        }
        boolean wantsOwnOrg = request.organisationName() != null && !request.organisationName().isBlank();
        if (invitations.isEmpty() || wantsOwnOrg) {
            Organisation org = new Organisation();
            org.name = wantsOwnOrg ? request.organisationName().trim() : user.name + "'s workspace";
            org.createdAt = now;
            org.persist();
            Membership.create(org.id, user.id, Role.OWNER, now);
        }
        return respond(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = User.findByEmail(request.email())
                .filter(u -> hasher.matches(request.password(), u.passwordHash))
                .orElseThrow(() -> new ApiException(401, "invalid_credentials", "Email or password is incorrect"));
        return respond(user);
    }

    private AuthResponse respond(User user) {
        TokenService.Token token = tokens.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserResponse.of(user));
    }
}
