package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;

/**
 * The signed-in user for the current request, read from the bearer token.
 * <p>
 * {@link #id()} is the one method every signed-in request goes through, so it is where a
 * token stops working: when the account is gone, or when the password was changed or
 * reset after the token was issued. That costs one lookup per request, made the first
 * time the id is asked for. Both times are compared in whole seconds, because the
 * issued-at time in a token has no fractions: a token issued in the same second as the
 * change still works, so signing in straight after a reset is not thrown out.
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
        // Only the time, not the entity: an entity loaded here, outside the transaction of
        // the endpoint, would not save what the endpoint changes on it.
        // A user without a change gives one row holding null, a deleted user no row.
        List<Instant> rows = entityManager
                .createQuery("select u.passwordChangedAt from User u where u.id = :id", Instant.class)
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) {
            throw ApiException.unauthorized("Your account no longer exists");
        }
        Instant changed = rows.get(0);
        if (changed != null && jwt.getIssuedAtTime() < changed.getEpochSecond()) {
            throw ApiException.unauthorized("Your password was changed. Sign in again.");
        }
        checkedId = id;
        return id;
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
