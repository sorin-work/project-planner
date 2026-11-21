package com.projectplanner.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Wrong passwords in a row for one account from one client address, see {@link LoginLimits}. */
@Entity
@Table(name = "login_failures")
public class LoginFailure extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(nullable = false)
    public String address;

    @Column(nullable = false)
    public int failures;

    @Column(name = "last_failed_at", nullable = false)
    public Instant lastFailedAt;
}
