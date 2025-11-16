package com.projectplanner.org;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "memberships")
public class Membership extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Role role;

    /** Overrides the role rate for this person. */
    @Column(name = "hourly_rate")
    public BigDecimal hourlyRate;

    @Column(name = "weekly_capacity_hours", nullable = false)
    public BigDecimal weeklyCapacityHours = BigDecimal.valueOf(40);

    /** Set for client viewers: the client whose projects they can see. */
    @Column(name = "client_id")
    public Long clientId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static Membership create(long organisationId, long userId, Role role, Instant now) {
        Membership membership = new Membership();
        membership.organisationId = organisationId;
        membership.userId = userId;
        membership.role = role;
        membership.createdAt = now;
        membership.persist();
        return membership;
    }

    public static Optional<Membership> find(long organisationId, long userId) {
        return find("organisationId = ?1 and userId = ?2", organisationId, userId).firstResultOptional();
    }

    public static List<Membership> forUser(long userId) {
        return list("userId = ?1 order by id", userId);
    }

    public static List<Membership> forOrganisation(long organisationId) {
        return list("organisationId = ?1 order by id", organisationId);
    }
}
