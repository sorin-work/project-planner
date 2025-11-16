package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import com.projectplanner.auth.AuthDtos.UserResponse;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;

@ApplicationScoped
public class AuthService {

    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final Clock clock;

    public AuthService(PasswordHasher hasher, TokenService tokens, Clock clock) {
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Creates the account and an organisation with the new user as its owner. */
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

        boolean named = request.organisationName() != null && !request.organisationName().isBlank();
        Organisation org = new Organisation();
        org.name = named ? request.organisationName().trim() : user.name + "'s workspace";
        org.createdAt = now;
        org.persist();
        Membership.create(org.id, user.id, Role.OWNER, now);
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
