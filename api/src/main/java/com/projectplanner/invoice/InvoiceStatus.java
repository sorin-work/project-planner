package com.projectplanner.invoice;

/** A draft can change; an issued invoice has its number and never changes. */
public enum InvoiceStatus {
    DRAFT,
    ISSUED
}
