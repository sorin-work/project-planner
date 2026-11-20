package com.projectplanner.org;

import com.projectplanner.auth.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * An invitation for someone who doesn't have an account yet. When the app sends mail,
 * the invitation is mailed with a link, see {@link Invitations}. Without mail, or unless
 * INVITE_LINK_REQUIRED is on, it is also accepted when they sign up with the invited
 * email address.
 */
@Entity
@Table(name = "invitations")
public class Invitation extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(nullable = false)
    public String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Role role;

    @Column(name = "client_id")
    public Long clientId;

    @Column(name = "invited_by")
    public Long invitedBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "accepted_at")
    public Instant acceptedAt;

    /** SHA-256 of the token in the link that was mailed last. Null when none was mailed. */
    @Column(name = "token_hash")
    public String tokenHash;

    @Column(name = "token_expires_at")
    public Instant tokenExpiresAt;

    @Column(name = "sent_at")
    public Instant sentAt;

    public static List<Invitation> pendingFor(String email) {
        return list("email = ?1 and acceptedAt is null order by id", User.normalizeEmail(email));
    }

    public static List<Invitation> pendingIn(long organisationId) {
        return list("organisationId = ?1 and acceptedAt is null order by createdAt desc", organisationId);
    }

    public void accept(User user, Instant now) {
        if (Membership.find(organisationId, user.id).isEmpty()) {
            Membership membership = Membership.create(organisationId, user.id, role, now);
            membership.clientId = clientId;
        }
        acceptedAt = now;
    }
}
