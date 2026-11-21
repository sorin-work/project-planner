package com.projectplanner.backup;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** That the operators were mailed about one state of the backups and one dump. */
@Entity
@Table(name = "backup_alert_marks")
public class BackupAlertMark extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String marker;

    @Column(name = "sent_at", nullable = false)
    public Instant sentAt;
}
