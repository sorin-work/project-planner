package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.AuthResponse;
import com.projectplanner.auth.AuthDtos.LoginRequest;
import com.projectplanner.auth.AuthDtos.SignupRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/api/auth")
@PermitAll
public class AuthResource {

    private final AuthService auth;

    public AuthResource(AuthService auth) {
        this.auth = auth;
    }

    @POST
    @Path("/signup")
    @ResponseStatus(201)
    public AuthResponse signup(@Valid @NotNull SignupRequest request) {
        return auth.signup(request);
    }

    @POST
    @Path("/login")
    public AuthResponse login(@Valid @NotNull LoginRequest request) {
        return auth.login(request);
    }
}
