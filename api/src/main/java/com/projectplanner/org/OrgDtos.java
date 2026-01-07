package com.projectplanner.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.auth.User;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
                                       BigDecimal defaultHourlyRate, Role role) {
        public static OrganisationResponse of(Organisation org, Membership membership) {
            BigDecimal rate = org.defaultHourlyRate;
            return new OrganisationResponse(org.id, org.name, org.currency, org.timezone, rate, membership.role);
        }
    }

    public record UpdateOrganisationRequest(
            @Size(min = 1, max = 120) String name,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO currency code") String currency,
            @Size(min = 1, max = 64) String timezone,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal defaultHourlyRate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberResponse(long id, long userId, String name, String email, String handle, Role role,
                                 BigDecimal hourlyRate, BigDecimal weeklyCapacityHours, Long clientId,
                                 Instant joinedAt) {
        public static MemberResponse of(Membership m, User user, boolean showRates) {
            return new MemberResponse(m.id, user.id, user.name, user.email, user.handle(), m.role,
                    showRates ? m.hourlyRate : null, m.weeklyCapacityHours, m.clientId, m.createdAt);
        }
    }

    public record UpdateMemberRequest(
            Role role,
            @DecimalMin("0") @DecimalMax("100000") BigDecimal hourlyRate,
            boolean clearHourlyRate,
            @DecimalMin("0") @DecimalMax("168") BigDecimal weeklyCapacityHours,
            Long clientId) {
    }

    public record InviteRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotNull Role role,
            Long clientId) {
    }

    public record InvitationResponse(long id, String email, Role role, Long clientId, Instant createdAt) {
        public static InvitationResponse of(Invitation i) {
            return new InvitationResponse(i.id, i.email, i.role, i.clientId, i.createdAt);
        }
    }

    /** Result of inviting someone: existing users are added straight away. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InviteResult(MemberResponse member, InvitationResponse invitation) {
    }

    public record RoleRateEntry(@NotNull Role role, @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal hourlyRate) {
    }

    public record RoleRatesRequest(@NotNull List<@NotNull RoleRateEntry> rates) {
    }
}
