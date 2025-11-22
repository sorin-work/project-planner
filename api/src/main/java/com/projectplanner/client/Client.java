package com.projectplanner.client;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "clients")
public class Client extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(nullable = false)
    public String name;

    @Column(name = "contact_name")
    public String contactName;

    @Column(name = "contact_email")
    public String contactEmail;

    public String notes;

    @Column(nullable = false)
    public boolean archived;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
