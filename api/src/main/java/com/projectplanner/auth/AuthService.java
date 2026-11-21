package com.projectplanner.auth;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import com.projectplanner.auth.AuthDtos.UserResponse;
import com.projectplanner.common.ApiException;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.org.Invitation;
import com.projectplanner.org.Invitations;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AuthService {

    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final ActivityLog activity;
    private final Invitations invitations;
    private final MailSettings mail;
    private final LoginLimits limits;
    private final Clock clock;

    public AuthService(PasswordHasher hasher, TokenService tokens, ActivityLog activity, Invitations invitations,
                       MailSettings mail, LoginLimits limits, Clock clock) {
        this.limits = limits;
        this.hasher = hasher;
        this.tokens = tokens;
        this.activity = activity;
        this.invitations = invitations;
        this.mail = mail;
        this.clock = clock;
    }

    /**
     * Creates the account. With an invitation link, the organisation of that invitation is
     * joined with its role, and the email has to be the invited address. Other invitations
     * waiting for the address are accepted too. Without a link, only invitations that were
     * never mailed (mail was off) are accepted; mailed ones wait for their link, which the
     * account can use after signing in. With {@link MailSettings#inviteLinkRequired()} nobody
     * signs up without a link. Without any invitation accepted, the user gets a new
     * organisation of their own as owner.
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = User.normalizeEmail(request.email());
        if (User.findByEmail(email).isPresent()) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        Instant now = clock.instant();
        String token = request.invitationToken();
        Invitation linked = token == null || token.isBlank() ? null : invitations.open(token, now, email);
        if (linked == null && mail.inviteLinkRequired()) {
            throw ApiException.conflict("invitation_required", "Signing up needs an invitation. Open the link in the invitation mail.");
        }
        // Invitations that were mailed are only ever accepted with their link: by the address
        // alone, whoever knows it could sign up first and take the role. Without the link the
        // account is made all the same, and those invitations stay waiting for it.
        List<Invitation> pending = Invitation.pendingFor(email).stream()
                .filter(invitation -> linked != null || invitation.tokenHash == null)
                .toList();
        User user = new User();
        user.email = email;
        user.name = request.name().trim();
        user.passwordHash = hasher.hash(request.password());
        user.createdAt = now;
        user.persist();

        List<Invitation> accepted = new ArrayList<>();
        if (linked != null) {
            invitations.acceptByLink(linked, user, now);
            accepted.add(linked);
        }
        // The others for the address: with the link the person showed that the mail reaches
        // them, and without mail there are only invitations that were never mailed.
        for (Invitation invitation : pending) {
            if (invitation != linked) {
                invitation.accept(user, now);
                accepted.add(invitation);
            }
        }
        for (Invitation invitation : accepted) {
            activity.record(invitation.organisationId, null, null, user.id, "member.joined",
                    user.name + " joined as " + invitation.role.label());
        }
        boolean wantsOwnOrg = request.organisationName() != null && !request.organisationName().isBlank();
        if (accepted.isEmpty() || wantsOwnOrg) {
            Organisation org = new Organisation();
            org.name = wantsOwnOrg ? request.organisationName().trim() : user.name + "'s workspace";
            org.createdAt = now;
            org.persist();
            Membership.create(org.id, user.id, Role.OWNER, now);
        }
        return respond(user);
    }

    /**
     * Signs in, within the limits of {@link LoginLimits}: only a wait of this account from this
     * client {@code address} refuses a try. An unknown email is checked against a dummy hash, so
     * that the time of the answer does not say whether there is an account.
     */
    public AuthResponse login(LoginRequest request, String address) {
        Instant now = clock.instant();
        Optional<User> found = User.findByEmail(request.email());
        if (found.isEmpty()) {
            hasher.matches(request.password(), hasher.dummyHash());
            limits.addressFailed(address, now);
            throw wrongPassword();
        }
        User user = found.get();
        limits.claim(user, address, now);
        if (!hasher.matches(request.password(), user.passwordHash)) {
            limits.failed(user, address, now);
            throw wrongPassword();
        }
        limits.succeeded(user, address, now);
        return respond(user);
    }

    private static ApiException wrongPassword() {
        return new ApiException(401, "invalid_credentials", "Email or password is incorrect");
    }

    /** A new token for the user, with the user. What signing in answers with. */
    public AuthResponse respond(User user) {
        TokenService.Token token = tokens.issue(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserResponse.of(user));
    }
}
