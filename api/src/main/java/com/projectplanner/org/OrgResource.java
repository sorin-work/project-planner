package com.projectplanner.org;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.org.OrgDtos.InvitationResponse;
import com.projectplanner.org.OrgDtos.InviteRequest;
import com.projectplanner.org.OrgDtos.InviteResult;
import com.projectplanner.org.OrgDtos.MemberResponse;
import com.projectplanner.org.OrgDtos.OrganisationResponse;
import com.projectplanner.org.OrgDtos.RoleRateEntry;
import com.projectplanner.org.OrgDtos.RoleRatesRequest;
import com.projectplanner.org.OrgDtos.UpdateMemberRequest;
import com.projectplanner.org.OrgDtos.UpdateOrganisationRequest;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Path("/api")
@Authenticated
public class OrgResource {

    private final OrgAccess access;
    private final ActivityLog activity;
    private final Clock clock;

    public OrgResource(OrgAccess access, ActivityLog activity, Clock clock) {
        this.access = access;
        this.activity = activity;
        this.clock = clock;
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

    @GET
    @Path("/orgs/{orgId}/invitations")
    public List<InvitationResponse> invitations(@PathParam("orgId") long orgId) {
        access.manager(orgId);
        return Invitation.pendingIn(orgId).stream().map(InvitationResponse::of).toList();
    }

    @POST
    @Path("/orgs/{orgId}/invitations")
    @Transactional
    @ResponseStatus(201)
    public InviteResult invite(@PathParam("orgId") long orgId, @Valid @NotNull InviteRequest request) {
        Membership me = access.manager(orgId);
        if (request.role() == Role.OWNER) {
            throw ApiException.badRequest("invalid_role", "Invite people as managers, members or client viewers");
        }
        Long clientId = null;
        if (request.role() == Role.CLIENT) {
            if (request.clientId() == null) {
                throw ApiException.badRequest("client_required", "Choose the client this viewer belongs to");
            }
            clientId = clientIn(orgId, request.clientId()).id;
        }
        String email = User.normalizeEmail(request.email());
        Instant now = clock.instant();
        var existing = User.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (Membership.find(orgId, user.id).isPresent()) {
                throw ApiException.conflict("already_member", user.name + " is already in this organisation");
            }
            Membership membership = Membership.create(orgId, user.id, request.role(), now);
            membership.clientId = clientId;
            activity.record(orgId, null, null, me.userId, "member.added", "Added " + user.name + " as " + request.role().label());
            return new InviteResult(MemberResponse.of(membership, user, true), null);
        }
        Invitation invitation = new Invitation();
        invitation.organisationId = orgId;
        invitation.email = email;
        invitation.role = request.role();
        invitation.clientId = clientId;
        invitation.invitedBy = me.userId;
        invitation.createdAt = now;
        invitation.persist();
        activity.record(orgId, null, null, me.userId, "member.invited", "Invited " + email + " as " + request.role().label());
        return new InviteResult(null, InvitationResponse.of(invitation));
    }

    @DELETE
    @Path("/orgs/{orgId}/invitations/{invitationId}")
    @Transactional
    @ResponseStatus(204)
    public void cancelInvitation(@PathParam("orgId") long orgId, @PathParam("invitationId") long invitationId) {
        access.manager(orgId);
        Invitation invitation = Invitation.findById(invitationId);
        if (invitation == null || invitation.organisationId != orgId || invitation.acceptedAt != null) {
            throw ApiException.notFound("Invitation");
        }
        invitation.delete();
    }

    @GET
    @Path("/orgs/{orgId}/role-rates")
    public List<RoleRateEntry> roleRates(@PathParam("orgId") long orgId) {
        access.manager(orgId);
        Map<Role, BigDecimal> rates = RoleRate.forOrganisation(orgId);
        return rates.entrySet().stream().map(e -> new RoleRateEntry(e.getKey(), e.getValue())).toList();
    }

    @PUT
    @Path("/orgs/{orgId}/role-rates")
    @Transactional
    public List<RoleRateEntry> setRoleRates(@PathParam("orgId") long orgId, @Valid @NotNull RoleRatesRequest request) {
        access.require(orgId, Role.OWNER);
        RoleRate.delete("organisationId", orgId);
        RoleRate.flush();
        for (RoleRateEntry entry : request.rates()) {
            RoleRate rate = new RoleRate();
            rate.organisationId = orgId;
            rate.role = entry.role();
            rate.hourlyRate = entry.hourlyRate();
            rate.persist();
        }
        return roleRates(orgId);
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
