package com.projectplanner.invoice;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of an invoice: time of one project and task, or of one project and person, at one
 * rate. The names are copied, so that a line still reads after its task, person or project
 * has gone. The amount is the sum of the entries priced one by one, as the reports do.
 */
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "invoice_id", nullable = false)
    public Long invoiceId;

    @Column(nullable = false)
    public int position;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "project_name", nullable = false)
    public String projectName;

    @Column(name = "task_title")
    public String taskTitle;

    @Column(name = "person_name")
    public String personName;

    @Column(nullable = false)
    public String description;

    @Column(nullable = false)
    public int minutes;

    @Column(name = "rate_cents", nullable = false)
    public long rateCents;

    @Column(name = "amount_cents", nullable = false)
    public long amountCents;
}
