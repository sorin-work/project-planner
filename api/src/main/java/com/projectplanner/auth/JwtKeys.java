package com.projectplanner.auth;

import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * One HMAC key, derived from JWT_SECRET, for signing and verifying tokens.
 * Replaces smallrye-jwt's config-based verification setup, which expects the
 * secret as a JWK rather than a plain string.
 */
@ApplicationScoped
public class JwtKeys {

    static final String ISSUER = "project-planner";

    private final SecretKey key;

    public JwtKeys(@ConfigProperty(name = "planner.jwt.secret") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public SecretKey key() {
        return key;
    }

    @Produces
    @Alternative
    @Priority(1)
    @ApplicationScoped
    JWTAuthContextInfo authContextInfo() {
        JWTAuthContextInfo info = new JWTAuthContextInfo();
        info.setSecretVerificationKey(key);
        info.setSignatureAlgorithm(Set.of(SignatureAlgorithm.HS256));
        info.setIssuedBy(ISSUER);
        return info;
    }
}
