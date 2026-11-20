package com.projectplanner.org;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.CurrentUser;
import com.projectplanner.auth.User;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.mail.MailSettings;
import com.projectplanner.org.OrgDtos.InvitationLinkRequest;
import com.projectplanner.org.OrgDtos.InvitationResponse;
import com.projectplanner.org.OrgDtos.InviteRequest;
import com.projectplanner.org.OrgDtos.InviteResult;
import com.projectplanner.org.OrgDtos.MemberResponse;
import com.projectplanner.org.OrgDtos.MembershipSummary;
import com.projectplanner.org.OrgDtos.OrganisationResponse;
import com.projectplanner.org.OrgDtos.RoleRateEntry;
import com.projectplanner.org.OrgDtos.RoleRatesRequest;
import com.projectplanner.org.OrgDtos.UpdateMemberRequest;
import com.projectplanner.org.OrgDtos.UpdateOrganisationRequest;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.event.Event;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Path("/api")
@Authenticated
@Tag(name = "Organisation")
public class OrgResource {

    private final OrgAccess access;
    private final ActivityLog activity;
    private final Invitations invitations;
    private final MailSettings mail;
    private final CurrentUser currentUser;
    private final Clock clock;
    /** A rate decides what logged time costs, so a change of one is told to whoever watches budgets. */
    private final Event<RatesChanged> ratesChanged;

    public OrgResource(OrgAccess access, ActivityLog activity, Invitations invitations, MailSettings mail,
                       CurrentUser currentUser, Clock clock, Event<RatesChanged> ratesChanged) {
        this.access = access;
        this.activity = activity;
        this.invitations = invitations;
        this.mail = mail;
        this.currentUser = currentUser;
        this.clock = clock;
        this.ratesChanged = ratesChanged;
    }

    @GET
    @Path("/orgs/{orgId}")
    @Operation(summary = "Get an organisation")
    public OrganisationResponse get(@PathParam("orgId") long orgId) {
        Membership membership = access.member(orgId);
        return OrganisationResponse.of(Organisation.findById(orgId), membership);
    }

    @PATCH
    @Path("/orgs/{orgId}")
    @Transactional
    @Operation(summary = "Change the settings of an organisation",
            description = "For owners. Fields that are left out stay as they are.")
    @Fails(status = 400, code = "invalid_timezone", when = "timezone is not a known time zone")
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
            try {
                org.timezone = ZoneId.of(request.timezone()).getId();
            } catch (DateTimeException e) {
                throw ApiException.badRequest("invalid_timezone", "Unknown time zone " + request.timezone());
            }
        }
        if (request.defaultHourlyRate() != null) {
            // The settings page always sends the rate, so only a different one counts as a change.
            boolean changed = !sameRate(org.defaultHourlyRate, request.defaultHourlyRate());
            org.defaultHourlyRate = request.defaultHourlyRate();
            if (changed) {
                ratesChanged.fire(new RatesChanged(orgId));
            }
        }
        return OrganisationResponse.of(org, membership);
    }

    @GET
    @Path("/orgs/{orgId}/members")
    @Operation(summary = "List the members",
            description = "Client viewers only get the team and themselves.")
    public List<MemberResponse> members(@PathParam("orgId") long orgId) {
        Membership me = access.member(orgId);
        List<Membership> memberships = Membership.forOrganisation(orgId);
        if (me.role == Role.CLIENT) {
            // Client viewers only need to know who is on the team, not other clients.
            memberships = memberships.stream().filter(m -> m.role.isTeam() || m.id.equals(me.id)).toList();
        }
        Map<Long, User> users = new HashMap<>();
        if (!memberships.isEmpty()) {
            User.<User>list("id in ?1", memberships.stream().map(m -> m.userId).toList()).forEach(u -> users.put(u.id, u));
        }
        return memberships.stream()
                .map(m -> MemberResponse.of(m, users.get(m.userId), me.role.canManage()))
                .sorted(Comparator.comparing(MemberResponse::role).thenComparing(MemberResponse::name))
                .toList();
    }

    @PATCH
    @Path("/orgs/{orgId}/members/{memberId}")
    @Transactional
    @Operation(summary = "Change the role, rate or capacity of a member",
            description = "For owners and managers. Only owners can grant or take away the owner role.")
    @Fails(status = 400, code = "unknown_client", when = "clientId is not a client of this organisation")
    @Fails(status = 409, code = "last_owner", when = "the member is the only owner and would stop being one")
    public MemberResponse updateMember(@PathParam("orgId") long orgId, @PathParam("memberId") long memberId,
                                       @Valid @NotNull UpdateMemberRequest request) {
        Membership me = access.manager(orgId);
        Membership member = memberIn(orgId, memberId);
        Role roleBefore = member.role;
        BigDecimal rateBefore = member.hourlyRate;
        if (request.role() != null && request.role() != member.role) {
            if (me.role != Role.OWNER && (request.role() == Role.OWNER || member.role == Role.OWNER)) {
                throw ApiException.forbidden("Only owners can grant or remove the owner role");
            }
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
        if (roleBefore != member.role || !sameRate(rateBefore, member.hourlyRate)) {
            // Their time is priced by their own rate, or else by the rate of their role.
            ratesChanged.fire(new RatesChanged(orgId, member.id));
        }
        return MemberResponse.of(member, User.findById(member.userId), true);
    }

    @DELETE
    @Path("/orgs/{orgId}/members/{memberId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Remove a member",
            description = "For owners and managers. Only owners can remove an owner.")
    @Fails(status = 409, code = "last_owner", when = "the member is the only owner")
    public void removeMember(@PathParam("orgId") long orgId, @PathParam("memberId") long memberId) {
        Membership me = access.manager(orgId);
        Membership member = memberIn(orgId, memberId);
        if (member.role == Role.OWNER && me.role != Role.OWNER) {
            throw ApiException.forbidden("Only owners can remove an owner");
        }
        if (member.role == Role.OWNER && countOwners(orgId) == 1) {
            throw ApiException.conflict("last_owner", "An organisation needs at least one owner");
        }
        User user = User.findById(member.userId);
        member.delete();
        activity.record(orgId, null, null, me.userId, "member.removed", "Removed " + user.name + " from the team");
        // The time they logged stays, and is priced at the organisation's default rate from now on.
        ratesChanged.fire(new RatesChanged(orgId));
    }

    @GET
    @Path("/orgs/{orgId}/invitations")
    @Operation(summary = "List the invitations nobody has accepted yet",
            description = "For owners and managers.")
    public List<InvitationResponse> invitations(@PathParam("orgId") long orgId) {
        access.manager(orgId);
        return Invitation.pendingIn(orgId).stream().map(InvitationResponse::of).toList();
    }

    @POST
    @Path("/orgs/{orgId}/invitations")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Invite someone by email",
            description = "For owners and managers. Someone who already has an account is added straight away and comes back as member. Anyone else gets an invitation. When the server sends mail, it is mailed with a link that works for seven days; the mails carry no names, only fixed wording and the link.")
    @Fails(status = 400, code = "invalid_role", when = "the role is OWNER")
    @Fails(status = 400, code = "client_required", when = "the role is CLIENT and clientId is missing")
    @Fails(status = 400, code = "unknown_client", when = "clientId is not a client of this organisation")
    @Fails(status = 409, code = "already_member", when = "the person is already in the organisation")
    @Fails(status = 409, code = "already_invited", when = "the email already has an invitation")
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
            invitations.mailAdded(user.email);
            return new InviteResult(MemberResponse.of(membership, user, true), null);
        }
        boolean pending = Invitation.pendingIn(orgId).stream().anyMatch(i -> i.email.equals(email));
        if (pending) {
            throw ApiException.conflict("already_invited", email + " has already been invited");
        }
        Invitation invitation = new Invitation();
        invitation.organisationId = orgId;
        invitation.email = email;
        invitation.role = request.role();
        invitation.clientId = clientId;
        invitation.invitedBy = me.userId;
        invitation.createdAt = now;
        invitation.persist();
        invitations.mailLink(invitation, now);
        activity.record(orgId, null, null, me.userId, "member.invited", "Invited " + email + " as " + request.role().label());
        return new InviteResult(null, InvitationResponse.of(invitation));
    }

    @POST
    @Path("/orgs/{orgId}/invitations/{invitationId}/resend")
    @Transactional
    @Operation(summary = "Mail an invitation again",
            description = "For owners and managers. The mail has a new link for seven days, and the link sent before stops working.")
    @Fails(status = 404, code = "mail_off", when = "the server sends no mail")
    @Fails(status = 409, code = "resend_too_soon", when = "the invitation was mailed less than a minute ago")
    public InvitationResponse resendInvitation(@PathParam("orgId") long orgId, @PathParam("invitationId") long invitationId) {
        access.manager(orgId);
        mail.requireEnabled();
        Invitation invitation = pendingInvitation(orgId, invitationId);
        Instant now = clock.instant();
        invitations.checkResend(invitation, now);
        invitations.mailLink(invitation, now);
        return InvitationResponse.of(invitation);
    }

    @DELETE
    @Path("/orgs/{orgId}/invitations/{invitationId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Cancel an invitation",
            description = "For owners and managers. Its link stops working at once.")
    public void cancelInvitation(@PathParam("orgId") long orgId, @PathParam("invitationId") long invitationId) {
        access.manager(orgId);
        pendingInvitation(orgId, invitationId).delete();
    }

    @POST
    @Path("/invitations/accept")
    @Transactional
    @Operation(summary = "Accept an invitation link with the account you are signed in with",
            description = "The invitation has to be for the address of your account. You join its organisation with its role.")
    @Fails(status = 400, code = "invalid_link", when = "the link expired, was cancelled, was already used or is for another address")
    public MembershipSummary acceptInvitation(@Valid @NotNull InvitationLinkRequest request) {
        User user = currentUser.get();
        Instant now = clock.instant();
        Invitation invitation = invitations.open(request.token(), now, user.email);
        boolean wasMember = Membership.find(invitation.organisationId, user.id).isPresent();
        invitations.acceptByLink(invitation, user, now);
        if (!wasMember) {
            activity.record(invitation.organisationId, null, null, user.id, "member.joined",
                    user.name + " joined as " + invitation.role.label());
        }
        return MembershipSummary.of(Membership.find(invitation.organisationId, user.id).orElseThrow(),
                Organisation.findById(invitation.organisationId));
    }

    @GET
    @Path("/orgs/{orgId}/role-rates")
    @Operation(summary = "List the default hourly rate per role",
            description = "For owners and managers.")
    public List<RoleRateEntry> roleRates(@PathParam("orgId") long orgId) {
        access.manager(orgId);
        Map<Role, BigDecimal> rates = RoleRate.forOrganisation(orgId);
        return rates.entrySet().stream().map(e -> new RoleRateEntry(e.getKey(), e.getValue())).toList();
    }

    @PUT
    @Path("/orgs/{orgId}/role-rates")
    @Transactional
    @Operation(summary = "Replace the default hourly rates per role",
            description = "For owners. Roles that are left out lose their rate.")
    public List<RoleRateEntry> setRoleRates(@PathParam("orgId") long orgId, @Valid @NotNull RoleRatesRequest request) {
        access.require(orgId, Role.OWNER);
        Map<Role, BigDecimal> before = RoleRate.forOrganisation(orgId);
        RoleRate.delete("organisationId", orgId);
        RoleRate.flush();
        Map<Role, BigDecimal> byRole = new java.util.EnumMap<>(Role.class);
        request.rates().forEach(entry -> byRole.put(entry.role(), entry.hourlyRate()));
        byRole.forEach((role, hourlyRate) -> {
            RoleRate rate = new RoleRate();
            rate.organisationId = orgId;
            rate.role = role;
            rate.hourlyRate = hourlyRate;
            rate.persist();
        });
        boolean changed = !before.keySet().equals(byRole.keySet())
                || byRole.entrySet().stream().anyMatch(e -> !sameRate(before.get(e.getKey()), e.getValue()));
        if (changed) {
            ratesChanged.fire(new RatesChanged(orgId));
        }
        return roleRates(orgId);
    }

    private static Invitation pendingInvitation(long orgId, long invitationId) {
        Invitation invitation = Invitation.findById(invitationId);
        if (invitation == null || invitation.organisationId != orgId || invitation.acceptedAt != null) {
            throw ApiException.notFound("Invitation");
        }
        return invitation;
    }

    /** 50 and 50.00 are the same rate. */
    private static boolean sameRate(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
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
