package com.projectplanner.attachment;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Short-lived links to the bytes of an attachment. A plain link cannot carry the bearer
 * token, so whoever may see the file asks for a link, and the link carries an HMAC over
 * the attachment, the user it was made for, its expiry and whether it shows the file in
 * the page. Access is checked when the link is made; the link itself is only good for
 * {@link #TTL}. The key is derived from the token secret, with a label of its own, so a
 * link can never be taken for a token or the other way round.
 */
@ApplicationScoped
public class SignedLinks {

    /** How long a link works. Long enough to start a download, short enough not to be worth sharing. */
    public static final Duration TTL = Duration.ofMinutes(2);

    private final byte[] key;
    private final Clock clock;

    public SignedLinks(@ConfigProperty(name = "planner.jwt.secret") String secret, Clock clock) {
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "attachment links v1");
        this.clock = clock;
    }

    /** What a link says, once its signature is checked. */
    public record Link(long attachmentId, long userId, long expires, boolean inline) {

        String payload() {
            return attachmentId + ":" + userId + ":" + expires + ":" + (inline ? 1 : 0);
        }
    }

    public Link issue(long attachmentId, long userId, boolean inline) {
        return new Link(attachmentId, userId, Instant.now(clock).plus(TTL).getEpochSecond(), inline);
    }

    public String signature(Link link) {
        return HexFormat.of().formatHex(hmac(key, link.payload()));
    }

    /** Whether {@code signature} belongs to the link and the link has not expired. */
    public boolean valid(Link link, String signature) {
        if (signature == null || link.expires() < Instant.now(clock).getEpochSecond()) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(given, hmac(key, link.payload()));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
