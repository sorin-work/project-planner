package com.projectplanner.auth;

import com.projectplanner.auth.AuthDtos.UserResponse;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgDtos.MembershipSummary;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import java.util.List;

@Path("/api/me")
@Authenticated
public class MeResource {

    private final CurrentUser currentUser;

    public MeResource(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GET
    public MeResponse me() {
        User user = currentUser.get();
        List<MembershipSummary> orgs = Membership.forUser(user.id).stream().map(MembershipSummary::of).toList();
        return new MeResponse(UserResponse.of(user), orgs);
    }

    public record MeResponse(UserResponse user, List<MembershipSummary> organisations) {
    }
}
