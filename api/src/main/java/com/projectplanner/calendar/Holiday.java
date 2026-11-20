package com.projectplanner.calendar;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.List;

/** A day nobody in the organisation works. */
@Entity
@Table(name = "holidays")
public class Holiday extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "holiday_date", nullable = false)
    public LocalDate date;

    @Column(nullable = false)
    public String name;

    public static List<Holiday> forOrganisation(long organisationId) {
        return list("organisationId = ?1 order by date", organisationId);
    }
}
