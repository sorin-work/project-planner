package com.projectplanner.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.GeneralSecurityException;
import java.util.UUID;

@ApplicationScoped
public class PasswordHasher {

    private static final int COST = 10;

    private volatile String dummyHash;

    public String hash(String password) {
        return BcryptUtil.bcryptHash(password, COST);
    }

    /**
     * A hash of no password anybody has, made with the same cost as the real ones. Signing in
     * with an unknown email checks the password against it, so that the answer takes as long
     * as for an account with a wrong password.
     */
    public String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = hash(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }
        try {
            Password decoded = ModularCrypt.decode(hash);
            PasswordFactory factory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
            BCryptPassword restored = (BCryptPassword) factory.translate(decoded);
            return factory.verify(restored, password.toCharArray());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
