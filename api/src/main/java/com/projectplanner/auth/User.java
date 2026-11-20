package com.projectplanner.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String email;

    @Column(nullable = false)
    public String name;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Tokens issued before this time no longer work. In whole seconds, see {@link CurrentUser}. */
    @Column(name = "password_changed_at")
    public Instant passwordChangedAt;

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static Optional<User> findByEmail(String email) {
        return find("email", normalizeEmail(email)).firstResultOptional();
    }

    /**
     * Sets a new password and ends every session that was started before, along with
     * every reset link that still works.
     */
    public void changePassword(String newHash, Instant now) {
        passwordHash = newHash;
        passwordChangedAt = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        PasswordReset.revokeAll(id, now);
    }

    /** The name used for @mentions: the part of the email before the @. */
    public String handle() {
        int at = email.indexOf('@');
        return (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
    }
}
