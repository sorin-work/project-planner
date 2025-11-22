package com.projectplanner.org;

import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.OrgDtos.MemberResponse;
import com.projectplanner.org.OrgDtos.OrganisationResponse;
import com.projectplanner.org.OrgDtos.UpdateOrganisationRequest;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Path("/api/orgs/{orgId}")
@Authenticated
public class OrgResource {

    private final OrgAccess access;

    public OrgResource(OrgAccess access) {
        this.access = access;
    }

    @GET
    public OrganisationResponse get(@PathParam("orgId") long orgId) {
        Membership membership = access.member(orgId);
        return OrganisationResponse.of(Organisation.findById(orgId), membership);
    }

    @PATCH
    @Transactional
    public OrganisationResponse update(@PathParam("orgId") long orgId, @Valid @NotNull UpdateOrganisationRequest request) {
        Membership membership = access.require(orgId, Role.OWNER);
        Organisation org = Organisation.findById(orgId);
        if (request.name() != null) {
            org.name = request.name().trim();
        }
        if (request.currency() != null) {
            org.currency = request.currency();
        }
        if (request.timezone() != null) {
            org.timezone = request.timezone();
        }
        if (request.defaultHourlyRate() != null) {
            org.defaultHourlyRate = request.defaultHourlyRate();
        }
        return OrganisationResponse.of(org, membership);
    }

    @GET
    @Path("/members")
    public List<MemberResponse> members(@PathParam("orgId") long orgId) {
        Membership me = access.member(orgId);
        List<Membership> memberships = Membership.forOrganisation(orgId);
        return memberships.stream()
                .map(m -> MemberResponse.of(m, User.findById(m.userId), true))
                .sorted(Comparator.comparing(MemberResponse::role).thenComparing(MemberResponse::name))
                .toList();
    }
}
