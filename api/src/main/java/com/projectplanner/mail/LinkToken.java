package com.projectplanner.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The secret in a link that is mailed: 32 random bytes from a cryptographic source. Only
 * its SHA-256 is stored, so a copy of the database holds no link that works.
 */
public record LinkToken(String value, String hash) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static LinkToken create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new LinkToken(value, hash(value));
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "LinkToken[hidden]";
    }
}
