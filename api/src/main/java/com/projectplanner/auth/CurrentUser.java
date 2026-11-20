package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonNumber;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;

/**
 * The signed-in user for the current request, read from the bearer token.
 * <p>
 * {@link #id()} is the one method every signed-in request goes through, so it is where a
 * token stops working: when the account is gone, or when the password was changed or
 * reset after the token was issued. A token carries the account's {@code tokenVersion},
 * which a change or a reset counts up, so every token issued before it stops working, even
 * one issued in the same second. That costs one lookup per request, made the first time the
 * id is asked for.
 */
@RequestScoped
public class CurrentUser {

    @Inject
    JsonWebToken jwt;

    @Inject
    EntityManager entityManager;

    private Long checkedId;
    private User user;

    public long id() {
        if (checkedId != null) {
            return checkedId;
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            throw ApiException.unauthorized("Sign in to continue");
        }
        long id = Long.parseLong(subject);
        // Only the version, not the entity: an entity loaded here, outside the transaction of
        // the endpoint, would not save what the endpoint changes on it.
        List<Integer> rows = entityManager
                .createQuery("select u.tokenVersion from User u where u.id = :id", Integer.class)
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) {
            throw ApiException.unauthorized("Your account no longer exists");
        }
        if (versionIn(jwt) != rows.get(0)) {
            throw ApiException.unauthorized("Your password was changed. Sign in again.");
        }
        checkedId = id;
        return id;
    }

    /** Tokens from before versions were carried have none, which is version 0. */
    private static long versionIn(JsonWebToken jwt) {
        Object claim = jwt.getClaim(TokenService.VERSION_CLAIM);
        if (claim instanceof JsonNumber number) {
            return number.longValue();
        }
        return claim instanceof Number number ? number.longValue() : 0;
    }

    public User get() {
        if (user == null) {
            user = User.findById(id());
            if (user == null) {
                throw ApiException.unauthorized("Your account no longer exists");
            }
        }
        return user;
    }
}
