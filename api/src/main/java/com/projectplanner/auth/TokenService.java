package com.projectplanner.auth;

import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    /** The claim with the account's {@code tokenVersion}. */
    static final String VERSION_CLAIM = "ver";

    private final JwtKeys keys;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(JwtKeys keys, @ConfigProperty(name = "planner.jwt.ttl") Duration ttl, Clock clock) {
        this.keys = keys;
        this.ttl = ttl;
        this.clock = clock;
    }

    public Token issue(User user) {
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        String token = Jwt.issuer(JwtKeys.ISSUER)
                .subject(String.valueOf(user.id))
                .upn(user.email)
                .claim("name", user.name)
                .claim(VERSION_CLAIM, user.tokenVersion)
                .groups(Set.of("user"))
                .issuedAt(now)
                .expiresAt(expires)
                .jws().algorithm(SignatureAlgorithm.HS256)
                .sign(keys.key());
        return new Token(token, expires);
    }

    public record Token(String value, Instant expiresAt) {
    }
}
