package com.projectplanner.auth;

import com.projectplanner.common.ApiException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The signed-in user for the current request, read from the bearer token. */
@RequestScoped
public class CurrentUser {

    @Inject
    JsonWebToken jwt;

    private User user;

    public long id() {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw ApiException.unauthorized("Sign in to continue");
        }
        return Long.parseLong(subject);
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
