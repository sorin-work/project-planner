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
import java.util.EnumMap;
import java.util.Map;

@Entity
@Table(name = "role_rates")
public class RoleRate extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Role role;

    @Column(name = "hourly_rate", nullable = false)
    public BigDecimal hourlyRate;

    public static Map<Role, BigDecimal> forOrganisation(long organisationId) {
        Map<Role, BigDecimal> rates = new EnumMap<>(Role.class);
        RoleRate.<RoleRate>list("organisationId", organisationId).forEach(r -> rates.put(r.role, r.hourlyRate));
        return rates;
    }
}
