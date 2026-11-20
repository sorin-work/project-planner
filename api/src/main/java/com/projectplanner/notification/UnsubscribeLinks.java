package com.projectplanner.notification;

import com.projectplanner.auth.JwtKeys;
import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The token in the "stop these mails" link of a notification mail. It says who and which
 * type, and is signed with a key of its own, derived from JWT_SECRET, so that it is good
 * for that one thing only: it is no login and cannot be turned into another person's or
 * another type's link. It does not expire, because a mail may be read late, and all it
 * can ever do is stop the mails of one type for one person.
 */
@ApplicationScoped
public class UnsubscribeLinks {

    private static final String PURPOSE = "unsubscribe";
    private static final Pattern PAYLOAD = Pattern.compile("unsubscribe:(\\d{1,18}):([A-Z_]{1,32})");

    private final SecretKeySpec key;

    public UnsubscribeLinks(JwtKeys keys) {
        this.key = new SecretKeySpec(mac(new SecretKeySpec(keys.key().getEncoded(), "HmacSHA256"),
                "project-planner " + PURPOSE + " links"), "HmacSHA256");
    }

    public record Subscription(long userId, NotificationType type) {
    }

    public String token(long userId, NotificationType type) {
        String payload = PURPOSE + ":" + userId + ":" + type.name();
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64.encodeToString(mac(key, payload));
    }

    /** Who and which type, when the token is one this server made. */
    public Optional<Subscription> read(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            Base64.Decoder base64 = Base64.getUrlDecoder();
            String payload = new String(base64.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = base64.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, mac(key, payload))) {
                return Optional.empty();
            }
            Matcher matcher = PAYLOAD.matcher(payload);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(new Subscription(Long.parseLong(matcher.group(1)), NotificationType.valueOf(matcher.group(2))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] mac(SecretKeySpec key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
