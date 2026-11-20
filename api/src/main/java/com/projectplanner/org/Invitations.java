package com.projectplanner.org;

import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.mail.LinkToken;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.mail.Mails;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;

/**
 * The mailed side of invitations. The link carries a random token, of which only the hash
 * is stored. It works for {@link #LIFETIME}, once, and stops working at once when the
 * invitation is cancelled or sent again. The organisation and the role are those of the
 * invitation; whoever accepts it cannot change them.
 */
@ApplicationScoped
public class Invitations {

    static final Duration LIFETIME = Duration.ofDays(7);
    static final String LIFETIME_TEXT = "seven days";
    static final Duration RESEND_GAP = Duration.ofMinutes(1);
    private static final String KIND = "invitation";

    private final MailSettings settings;
    private final Mails mails;
    private final MailOutbox outbox;

    public Invitations(MailSettings settings, Mails mails, MailOutbox outbox) {
        this.settings = settings;
        this.mails = mails;
        this.outbox = outbox;
    }

    /**
     * Gives the invitation a new link and mails it once the transaction has committed. A
     * link mailed before stops working. Does nothing when the app sends no mail.
     */
    public void mailLink(Invitation invitation, Instant now) {
        if (!settings.enabled()) {
            return;
        }
        LinkToken token = LinkToken.create();
        invitation.tokenHash = token.hash();
        invitation.tokenExpiresAt = now.plus(LIFETIME);
        invitation.sentAt = now;
        outbox.sendAfterCommit(KIND, mails.invitation(invitation.email, token.value(), LIFETIME_TEXT));
    }

    /** Tells somebody who already had an account that they were added. */
    public void mailAdded(String email) {
        if (settings.enabled()) {
            outbox.sendAfterCommit("added to an organisation", mails.addedToOrganisation(email));
        }
    }

    /** Refuses a resend within a minute of the last mail, so that a click too many sends nothing. */
    public void checkResend(Invitation invitation, Instant now) {
        if (invitation.sentAt != null && invitation.sentAt.isAfter(now.minus(RESEND_GAP))) {
            throw ApiException.conflict("resend_too_soon", "The invitation was sent less than a minute ago");
        }
    }

    /**
     * The invitation a link belongs to, while it can be accepted. With {@code email}, only
     * when it is for that address. Every way a link can fail gives the same answer.
     */
    public Invitation open(String token, Instant now, String email) {
        Invitation invitation = Invitation.<Invitation>find("tokenHash = ?1 and acceptedAt is null and tokenExpiresAt > ?2",
                LinkToken.hash(token), now).firstResult();
        if (invitation == null || (email != null && !invitation.email.equals(email))) {
            throw invalidLink();
        }
        return invitation;
    }

    /**
     * Accepts an invitation that came with its link. The link is used up by one update that
     * only matches while it still works, so of two requests with the same link only one
     * gets through, and the other fails without changing anything.
     */
    public void acceptByLink(Invitation invitation, User user, Instant now) {
        int used = Invitation.update("acceptedAt = ?1 where id = ?2 and tokenHash = ?3 and acceptedAt is null and tokenExpiresAt > ?1",
                now, invitation.id, invitation.tokenHash);
        if (used != 1) {
            throw invalidLink();
        }
        invitation.accept(user, now);
    }

    private static ApiException invalidLink() {
        return ApiException.badRequest("invalid_link", "This invitation has expired, was cancelled or was already accepted.");
    }
}
