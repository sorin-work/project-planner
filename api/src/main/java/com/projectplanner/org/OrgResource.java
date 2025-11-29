package com.projectplanner.org;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.OrgDtos.MemberResponse;
import com.projectplanner.org.OrgDtos.OrganisationResponse;
import com.projectplanner.org.OrgDtos.UpdateMemberRequest;
import com.projectplanner.org.OrgDtos.UpdateOrganisationRequest;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Path("/api")
@Authenticated
public class OrgResource {

    private final OrgAccess access;
    private final ActivityLog activity;

    public OrgResource(OrgAccess access, ActivityLog activity) {
        this.access = access;
        this.activity = activity;
    }

    @GET
    @Path("/orgs/{orgId}")
    public OrganisationResponse get(@PathParam("orgId") long orgId) {
        Membership membership = access.member(orgId);
        return OrganisationResponse.of(Organisation.findById(orgId), membership);
    }

    @PATCH
    @Path("/orgs/{orgId}")
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
    @Path("/orgs/{orgId}/members")
    public List<MemberResponse> members(@PathParam("orgId") long orgId) {
        Membership me = access.member(orgId);
        List<Membership> memberships = Membership.forOrganisation(orgId);
        return memberships.stream()
                .map(m -> MemberResponse.of(m, User.findById(m.userId), true))
                .sorted(Comparator.comparing(MemberResponse::role).thenComparing(MemberResponse::name))
                .toList();
    }

    @PATCH
    @Path("/orgs/{orgId}/members/{memberId}")
    @Transactional
    public MemberResponse updateMember(@PathParam("orgId") long orgId, @PathParam("memberId") long memberId,
                                       @Valid @NotNull UpdateMemberRequest request) {
        Membership me = access.manager(orgId);
        Membership member = memberIn(orgId, memberId);
        if (request.role() != null && request.role() != member.role) {
            if (member.role == Role.OWNER && countOwners(orgId) == 1) {
                throw ApiException.conflict("last_owner", "An organisation needs at least one owner");
            }
            member.role = request.role();
        }
        if (request.clearHourlyRate()) {
            member.hourlyRate = null;
        } else if (request.hourlyRate() != null) {
            member.hourlyRate = request.hourlyRate();
        }
        if (request.weeklyCapacityHours() != null) {
            member.weeklyCapacityHours = request.weeklyCapacityHours();
        }
        if (member.role == Role.CLIENT) {
            if (request.clientId() != null) {
                member.clientId = clientIn(orgId, request.clientId()).id;
            }
        } else {
            member.clientId = null;
        }
        return MemberResponse.of(member, User.findById(member.userId), true);
    }

    @DELETE
    @Path("/orgs/{orgId}/members/{memberId}")
    @Transactional
    @ResponseStatus(204)
    public void removeMember(@PathParam("orgId") long orgId, @PathParam("memberId") long memberId) {
        Membership me = access.manager(orgId);
        Membership member = memberIn(orgId, memberId);
        if (member.role == Role.OWNER && countOwners(orgId) == 1) {
            throw ApiException.conflict("last_owner", "An organisation needs at least one owner");
        }
        User user = User.findById(member.userId);
        member.delete();
        activity.record(orgId, null, null, me.userId, "member.removed", "Removed " + user.name + " from the team");
    }

    private long countOwners(long orgId) {
        return Membership.count("organisationId = ?1 and role = ?2", orgId, Role.OWNER);
    }

    private static Membership memberIn(long orgId, long memberId) {
        Membership member = Membership.findById(memberId);
        if (member == null || !Objects.equals(member.organisationId, orgId)) {
            throw ApiException.notFound("Member");
        }
        return member;
    }

    private static Client clientIn(long orgId, long clientId) {
        Client client = Client.findById(clientId);
        if (client == null || !Objects.equals(client.organisationId, orgId)) {
            throw ApiException.badRequest("unknown_client", "Client not found in this organisation");
        }
        return client;
    }
}
