package com.projectplanner.calendar;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Days one person is away, first and last day included. It lowers their capacity and moves no plan. */
@Entity
@Table(name = "time_off")
public class TimeOff extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "first_day", nullable = false)
    public LocalDate firstDay;

    @Column(name = "last_day", nullable = false)
    public LocalDate lastDay;

    public String note;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
