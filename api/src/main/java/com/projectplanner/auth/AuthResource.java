package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import com.projectplanner.common.Fails;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/api/auth")
@PermitAll
@Tag(name = "Auth")
public class AuthResource {

    private final AuthService auth;

    public AuthResource(AuthService auth) {
        this.auth = auth;
    }

    @POST
    @Path("/signup")
    @ResponseStatus(201)
    @Operation(summary = "Create an account",
            description = "Invitations waiting for the email are accepted on the spot. Without any, or when organisationName is given, a new organisation is created with you as its owner.")
    @Fails(status = 409, code = "email_taken", when = "there is already an account with this email")
    public AuthResponse signup(@Valid @NotNull SignupRequest request) {
        return auth.signup(request);
    }

    @POST
    @Path("/login")
    @Operation(summary = "Log in")
    @Fails(status = 401, code = "invalid_credentials", when = "the email or the password is wrong")
    public AuthResponse login(@Valid @NotNull LoginRequest request) {
        return auth.login(request);
    }
}
