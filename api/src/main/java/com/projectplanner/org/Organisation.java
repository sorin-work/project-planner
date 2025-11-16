package com.projectplanner.org;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Entity
@Table(name = "organisations")
public class Organisation extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String currency = "EUR";

    @Column(nullable = false)
    public String timezone = "UTC";

    @Column(name = "default_hourly_rate", nullable = false)
    public BigDecimal defaultHourlyRate = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public LocalDate today(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(zone()));
    }
}
