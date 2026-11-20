package com.projectplanner.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A mailed link to set a new password. Only the hash of its token is kept. */
@Entity
@Table(name = "password_resets")
public class PasswordReset extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "token_hash", nullable = false)
    public String tokenHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "used_at")
    public Instant usedAt;

    /** Links of the user that still work. */
    static long countLive(long userId, Instant now) {
        return count("userId = ?1 and usedAt is null and expiresAt > ?2", userId, now);
    }

    /** Uses up every link of the user that still works. */
    static void revokeAll(long userId, Instant now) {
        update("usedAt = ?1 where userId = ?2 and usedAt is null", now, userId);
    }
}
