package com.projectplanner.invoice;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    /** A draft for a client and a period. Without groupBy the lines are per project and task. */
    public record DraftRequest(@NotNull Long clientId, @NotNull LocalDate from, @NotNull LocalDate to, InvoiceGrouping groupBy,
                               @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRatePercent,
                               @Size(max = 2000) String notes) {
    }

    /** Changes to a draft. Only the fields that are sent change. */
    public record DraftChange(@DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRatePercent,
                              @Size(max = 2000) String notes) {
    }

    public record LineChange(@NotBlank @Size(max = 500) String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineResponse(long id, int position, Long projectId, String projectName, String taskTitle, String personName,
                               String description, int minutes, long rateCents, long amountCents) {

        static LineResponse of(InvoiceLine l) {
            return new LineResponse(l.id, l.position, l.projectId, l.projectName, l.taskTitle, l.personName, l.description,
                    l.minutes, l.rateCents, l.amountCents);
        }
    }

    /** A project of the client and period that is left out of a draft, and why. */
    public record LeftOut(long projectId, String projectName, String reason) {
    }

    /**
     * Amounts are in cents of {@code currency}. {@code number} is there once the invoice is
     * issued. A credit note names the invoice it cancels; an invoice that was cancelled names
     * its credit note.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvoiceResponse(long id, InvoiceKind kind, InvoiceStatus status, Integer number, long clientId,
                                  String clientName, LocalDate periodFrom, LocalDate periodTo, String currency,
                                  InvoiceGrouping groupBy, BigDecimal taxRatePercent, long subtotalCents, long taxCents,
                                  long totalCents, String notes, Instant createdAt, Instant issuedAt, Long creditsInvoiceId,
                                  Integer creditsInvoiceNumber, Long creditedById, Integer creditedByNumber,
                                  List<LineResponse> lines, List<LeftOut> leftOut) {
    }

    /** One row of the list, without its lines. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvoiceSummary(long id, InvoiceKind kind, InvoiceStatus status, Integer number, long clientId,
                                 String clientName, LocalDate periodFrom, LocalDate periodTo, String currency, long totalCents,
                                 Instant issuedAt, Long creditsInvoiceId, Long creditedById) {
    }
}
