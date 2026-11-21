package com.projectplanner.invoice;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** An invoice or a credit note for one client and period. Amounts are in cents of {@link #currency}. */
@Entity
@Table(name = "invoices")
public class Invoice extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "organisation_id", nullable = false)
    public Long organisationId;

    @Column(name = "client_id", nullable = false)
    public Long clientId;

    @Column(name = "client_name", nullable = false)
    public String clientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public InvoiceKind kind = InvoiceKind.INVOICE;

    @Column(name = "credits_invoice_id")
    public Long creditsInvoiceId;

    public Integer number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "period_from", nullable = false)
    public LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    public LocalDate periodTo;

    @Column(nullable = false)
    public String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_by", nullable = false)
    public InvoiceGrouping groupBy;

    /** The tax rate in basis points: 2000 is 20%. */
    @Column(name = "tax_rate_bp", nullable = false)
    public int taxRateBp;

    @Column(name = "subtotal_cents", nullable = false)
    public long subtotalCents;

    @Column(name = "tax_cents", nullable = false)
    public long taxCents;

    @Column(name = "total_cents", nullable = false)
    public long totalCents;

    public String notes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "issued_at")
    public Instant issuedAt;
}
