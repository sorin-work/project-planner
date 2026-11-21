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

    /** Carried in every login token. A token with an older version no longer works, see {@link CurrentUser}. */
    @Column(name = "token_version", nullable = false)
    public int tokenVersion;

    /** Wrong passwords from every address together, see {@link LoginLimits}. A right one or a new password sets it back to zero. */
    @Column(name = "failed_logins", nullable = false)
    public int failedLogins;

    @Column(name = "last_failed_login_at")
    public Instant lastFailedLoginAt;

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static Optional<User> findByEmail(String email) {
        return find("email", normalizeEmail(email)).firstResultOptional();
    }

    /**
     * Sets a new password and ends every session that was started before, along with
     * every reset link that still works. The wrong passwords counted against the account
     * are forgotten, so that a reset lets the owner straight in when somebody else kept it
     * waiting.
     * <p>
     * The version is counted up by the database, not read and written back here: the update
     * holds the row until the transaction ends, so a reset and a change at the same moment
     * end two versions further on, and neither leaves a session alive that the other ended.
     * The entity is then read again, so that the token for this session carries the new
     * version.
     */
    public void changePassword(String newHash, Instant now) {
        update("tokenVersion = tokenVersion + 1 where id = ?1", id);
        getEntityManager().refresh(this);
        passwordHash = newHash;
        failedLogins = 0;
        lastFailedLoginAt = null;
        LoginFailure.delete("userId", id);
        PasswordReset.revokeAll(id, now);
    }

    /** The name used for @mentions: the part of the email before the @. */
    public String handle() {
        int at = email.indexOf('@');
        return (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
    }
}
