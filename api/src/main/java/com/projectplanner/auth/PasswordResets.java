package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import com.projectplanner.mail.LinkToken;
import com.projectplanner.mail.MailOutbox;
import com.projectplanner.mail.Mails;
import io.quarkus.mailer.MailTemplate.MailTemplateInstance;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Forgotten passwords: a mailed link that sets a new one.
 * <p>
 * Asking for a link answers the same way, and in the same time, whether or not there is
 * an account for the address: looking up the account, making the link and sending it
 * happen on the mail thread after the answer is ready. An account gets at most
 * {@link #MAX_LIVE} links that work at the same time and at most one a minute. A new
 * request never cancels a link that was sent before, so that nobody can keep the link in
 * somebody else's inbox dead by asking again and again. Requests beyond the limits are
 * dropped without a word, since saying so would tell that the account exists.
 */
@ApplicationScoped
public class PasswordResets {

    static final Duration LIFETIME = Duration.ofHours(1);
    static final String LIFETIME_TEXT = "one hour";
    static final int MAX_LIVE = 3;
    static final Duration MIN_GAP = Duration.ofMinutes(1);
    private static final String KIND = "password reset";

    private final PasswordHasher hasher;
    private final Mails mails;
    private final MailOutbox outbox;
    private final Clock clock;

    public PasswordResets(PasswordHasher hasher, Mails mails, MailOutbox outbox, Clock clock) {
        this.hasher = hasher;
        this.mails = mails;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Hands the request to the mail thread and returns straight away. */
    public void request(String email) {
        String address = User.normalizeEmail(email);
        outbox.run(KIND, () -> QuarkusTransaction.requiringNew().call(() -> newLink(address))
                .ifPresent(mail -> outbox.send(KIND, mail)));
    }

    private Optional<MailTemplateInstance> newLink(String email) {
        // The lock keeps two requests at the same moment from both getting past the limits.
        User user = User.<User>find("email", email).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (user == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (PasswordReset.countLive(user.id, now) >= MAX_LIVE
                || PasswordReset.count("userId = ?1 and createdAt > ?2", user.id, now.minus(MIN_GAP)) > 0) {
            return Optional.empty();
        }
        LinkToken token = LinkToken.create();
        PasswordReset reset = new PasswordReset();
        reset.userId = user.id;
        reset.tokenHash = token.hash();
        reset.createdAt = now;
        reset.expiresAt = now.plus(LIFETIME);
        reset.persist();
        return Optional.of(mails.passwordReset(user.email, token.value(), LIFETIME_TEXT));
    }

    /**
     * Sets the new password. The link is used up by a single update that only matches a
     * link that still works, so two requests with the same link cannot both get through.
     * Every older session and every other link of the account stop working.
     */
    @Transactional
    public void reset(String token, String newPassword) {
        Instant now = clock.instant();
        String hash = LinkToken.hash(token);
        int used = PasswordReset.update("usedAt = ?1 where tokenHash = ?2 and usedAt is null and expiresAt > ?1", now, hash);
        if (used != 1) {
            throw ApiException.badRequest("invalid_link", "This link has expired or was already used. Ask for a new one.");
        }
        PasswordReset reset = PasswordReset.find("tokenHash", hash).firstResult();
        User user = User.findById(reset.userId);
        user.changePassword(hasher.hash(newPassword), now);
    }
}
