package com.projectplanner.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.GeneralSecurityException;

@ApplicationScoped
public class PasswordHasher {

    private static final int COST = 10;

    public String hash(String password) {
        return BcryptUtil.bcryptHash(password, COST);
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
