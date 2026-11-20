package com.projectplanner.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiDocs;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrgDtos {

    private OrgDtos() {
    }

    public record MembershipSummary(long organisationId, String organisationName, Role role, String currency) {
        public static MembershipSummary of(Membership membership) {
            Organisation org = Organisation.findById(membership.organisationId);
            return new MembershipSummary(org.id, org.name, membership.role, org.currency);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrganisationResponse(long id, String name, String currency, String timezone,
                                       @Schema(description = "The rate for time that no project, member or role rate covers. " + ApiDocs.MANAGERS_ONLY) BigDecimal defaultHourlyRate,
                                       @Schema(description = "Your own role in the organisation.") Role role) {
        public static OrganisationResponse of(Organisation org, Membership membership) {
            BigDecimal rate = membership.role.canManage() ? org.defaultHourlyRate : null;
            return new OrganisationResponse(org.id, org.name, org.currency, org.timezone, rate, membership.role);
        }
    }

    @Schema(description = "Only the fields that are sent change.")
    public record UpdateOrganisationRequest(
            @Size(min = 1, max = 120) String name,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO currency code") String currency,
            @Size(min = 1, max = 64) String timezone,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal defaultHourlyRate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberResponse(long id, long userId, String name, String email, String handle, Role role,
                                 @Schema(description = "The member's own rate, when they have one. " + ApiDocs.MANAGERS_ONLY) BigDecimal hourlyRate,
                                 BigDecimal weeklyCapacityHours,
                                 @Schema(description = "For a client viewer, the client they belong to.") Long clientId,
                                 Instant joinedAt) {
        public static MemberResponse of(Membership m, User user, boolean showRates) {
            return new MemberResponse(m.id, user.id, user.name, user.email, user.handle(), m.role,
                    showRates ? m.hourlyRate : null, m.weeklyCapacityHours, m.clientId, m.createdAt);
        }
    }

    @Schema(description = "Only the fields that are sent change. A rate cannot be set back to nothing by sending null, which is what clearHourlyRate is for.")
    public record UpdateMemberRequest(
            Role role,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal hourlyRate,
            @Schema(description = ApiDocs.CLEARS) boolean clearHourlyRate,
            @DecimalMin("0") @DecimalMax("168") BigDecimal weeklyCapacityHours,
            Long clientId) {
    }

    public record InviteRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotNull Role role,
            Long clientId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationResponse(long id, String email, Role role, Long clientId, Instant createdAt,
                                     @Schema(description = "When the link in the last invitation mail stops working. Left out when no mail was sent.") Instant linkExpiresAt) {
        public static InvitationResponse of(Invitation i) {
            return new InvitationResponse(i.id, i.email, i.role, i.clientId, i.createdAt, i.tokenExpiresAt);
        }
    }

    @Schema(description = "The token from the link in an invitation mail: the part after #token=.")
    public record InvitationLinkRequest(@NotBlank @Size(max = 100) String token) {
    }

    @Schema(description = "What an invitation link is for, so that the page can ask the right thing.")
    public record InvitationLinkResponse(
            @Schema(description = "The invited address. Only that account can accept.") String email,
            String organisationName,
            Role role,
            @Schema(description = "Whether the invited address already has an account. Then it signs in to accept, otherwise it signs up.") boolean hasAccount) {
    }

    /** Result of inviting someone: existing users are added straight away. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One of the two, never both.")
    public record InviteResult(
            @Schema(description = "The person already had an account and is in the organisation now.") MemberResponse member,
            @Schema(description = "Nobody has an account with that email yet. When the server sends mail, they get a link; see GET /api/auth/options.") InvitationResponse invitation) {
    }

    public record RoleRateEntry(@NotNull Role role, @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal hourlyRate) {
    }

    public record RoleRatesRequest(@NotNull List<@NotNull RoleRateEntry> rates) {
    }
}
