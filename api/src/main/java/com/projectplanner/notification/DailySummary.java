package com.projectplanner.notification;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * That one person's morning summary for one organisation and day was taken on. Written before
 * the mail goes out, so that the unique key lets only one instance of the API send it.
 * {@code sentAt} stays null when there was nothing to say or the mail did not go out.
 */
@Entity
@Table(name = "daily_summaries")
public class DailySummary extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "summary_day", nullable = false)
    public LocalDate day;

    @Column(name = "sent_at")
    public Instant sentAt;
}
