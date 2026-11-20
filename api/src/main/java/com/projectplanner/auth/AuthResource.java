package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.AuthOptions;
import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.ForgotRequest;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.ResetRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.mail.ClientThrottle;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.notification.Delivery;
import com.projectplanner.notification.NotificationPreferences;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.UnsubscribeLinks;
import com.projectplanner.org.Invitation;
import com.projectplanner.org.Invitations;
import com.projectplanner.org.OrgDtos.InvitationLinkRequest;
import com.projectplanner.org.OrgDtos.InvitationLinkResponse;
import com.projectplanner.org.Organisation;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;

@Path("/api/auth")
@PermitAll
@Tag(name = "Auth")
public class AuthResource {

    private final AuthService auth;
    private final PasswordResets resets;
    private final Invitations invitations;
    private final MailSettings mail;
    private final ClientThrottle throttle;
    private final UnsubscribeLinks unsubscribeLinks;
    private final NotificationPreferences preferences;
    private final Clock clock;

    public AuthResource(AuthService auth, PasswordResets resets, Invitations invitations, MailSettings mail,
                        ClientThrottle throttle, UnsubscribeLinks unsubscribeLinks, NotificationPreferences preferences,
                        Clock clock) {
        this.auth = auth;
        this.resets = resets;
        this.invitations = invitations;
        this.mail = mail;
        this.throttle = throttle;
        this.unsubscribeLinks = unsubscribeLinks;
        this.preferences = preferences;
        this.clock = clock;
    }

    @GET
    @Path("/options")
    @Operation(summary = "What this server offers before signing in",
            description = "The web app hides the links to reset a password when the server sends no mail.")
    public AuthOptions options() {
        return new AuthOptions(mail.enabled(), mail.inviteLinkRequired());
    }

    @POST
    @Path("/signup")
    @ResponseStatus(201)
    @Operation(summary = "Create an account",
            description = "With invitationToken, the organisation of that invitation is joined with its role, and other invitations waiting for the email are accepted on the spot too. Without it, invitations that were mailed are not accepted: they wait for their link, which the account can use after signing in. A server that requires invitations (GET /api/auth/options) needs a link for every sign-up. Without any invitation, or when organisationName is given, a new organisation is created with you as its owner.")
    @Fails(status = 409, code = "email_taken", when = "there is already an account with this email")
    @Fails(status = 400, code = "invalid_link", when = "invitationToken is not a link that works for this email: it expired, was cancelled, was already used or is for another address")
    @Fails(status = 409, code = "invitation_required", when = "there is no invitationToken and the server only lets people in with an invitation")
    public AuthResponse signup(@Valid @NotNull SignupRequest request) {
        return auth.signup(request);
    }

    @POST
    @Path("/login")
    @Operation(summary = "Log in")
    @Fails(status = 401, code = "invalid_credentials", when = "the email or the password is wrong")
    public AuthResponse login(@Valid @NotNull LoginRequest request) {
        return auth.login(request);
    }

    @POST
    @Path("/forgot")
    @ResponseStatus(204)
    @Operation(summary = "Ask for a link to reset a password",
            description = "Answers the same way whether or not there is an account for the email. If there is, a link that works for one hour is mailed to it, unless three links of the account still work or one was sent in the last minute. Links sent before keep working.")
    @Fails(status = 404, code = "mail_off", when = "the server sends no mail")
    @Fails(status = 429, code = "too_many_requests", when = "this client asked too often in the last 15 minutes")
    public void forgot(@Valid @NotNull ForgotRequest request, @Context HttpServerRequest http) {
        mail.requireEnabled();
        throttle.check(http.remoteAddress().hostAddress());
        resets.request(request.email());
    }

    @POST
    @Path("/reset")
    @ResponseStatus(204)
    @Operation(summary = "Set a new password with the link from the mail",
            description = "Every session of the account that was started before ends, and its other reset links stop working.")
    @Fails(status = 404, code = "mail_off", when = "the server sends no mail")
    @Fails(status = 400, code = "invalid_link", when = "the link expired or was already used")
    public void reset(@Valid @NotNull ResetRequest request) {
        mail.requireEnabled();
        resets.reset(request.token(), request.password());
    }

    @POST
    @Path("/invitation")
    @Operation(summary = "Read an invitation link",
            description = "Says which address the invitation is for and whether that address has an account, so that the page can ask to sign in or to sign up. The link is not used up.")
    @Fails(status = 400, code = "invalid_link", when = "the link expired, was cancelled or was already used")
    public InvitationLinkResponse invitation(@Valid @NotNull InvitationLinkRequest request) {
        Invitation invitation = invitations.open(request.token(), clock.instant(), null);
        Organisation org = Organisation.findById(invitation.organisationId);
        return new InvitationLinkResponse(invitation.email, org.name, invitation.role,
                User.findByEmail(invitation.email).isPresent());
    }

    @POST
    @Path("/unsubscribe")
    @Transactional
    @Operation(summary = "Stop the mails of one type, with the link from a notification mail",
            description = "Needs no sign-in: the token says whose mails and which type, and is signed for this one use. That type is then shown in the app only. The page asks before it sends this, so that a mail scanner that opens the link changes nothing.")
    @Fails(status = 400, code = "invalid_link", when = "the token was not made by this server, or the account is gone")
    public UnsubscribeResponse unsubscribe(@Valid @NotNull UnsubscribeRequest request) {
        UnsubscribeLinks.Subscription subscription = unsubscribeLinks.read(request.token())
                .filter(s -> User.findById(s.userId()) != null)
                .orElseThrow(() -> ApiException.badRequest("invalid_link", "This link is not one of ours. Change your mails in the settings instead."));
        if (preferences.of(subscription.userId(), subscription.type()) == Delivery.EMAIL) {
            preferences.set(subscription.userId(), subscription.type(), Delivery.IN_APP);
        }
        return new UnsubscribeResponse(subscription.type());
    }

    public record UnsubscribeRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record UnsubscribeResponse(@Schema(description = "The type that is no longer mailed.") NotificationType type) {
    }
}
