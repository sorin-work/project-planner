package com.projectplanner.auth;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@ApplicationScoped
public class TokenService {

    static final String ISSUER = "project-planner";

    private final String secret;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(@ConfigProperty(name = "planner.jwt.secret") String secret,
                        @ConfigProperty(name = "planner.jwt.ttl") Duration ttl,
                        Clock clock) {
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
        }
        this.secret = secret;
        this.ttl = ttl;
        this.clock = clock;
    }

    public Token issue(User user) {
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        String token = Jwt.issuer(ISSUER)
                .subject(String.valueOf(user.id))
                .upn(user.email)
                .claim("name", user.name)
                .groups(Set.of("user"))
                .issuedAt(now)
                .expiresAt(expires)
                .signWithSecret(secret);
        return new Token(token, expires);
    }

    public record Token(String value, Instant expiresAt) {
    }
}
