package com.projectplanner.invoice;

import com.projectplanner.client.Client;
import com.projectplanner.common.CsvWriter;
import com.projectplanner.common.Fails;
import com.projectplanner.common.Money;
import com.projectplanner.invoice.InvoiceDtos.DraftChange;
import com.projectplanner.invoice.InvoiceDtos.DraftRequest;
import com.projectplanner.invoice.InvoiceDtos.InvoiceResponse;
import com.projectplanner.invoice.InvoiceDtos.InvoiceSummary;
import com.projectplanner.invoice.InvoiceDtos.LineChange;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/** Invoices from approved, billable time. For owners and managers; client viewers see none. */
@Path("/api/orgs/{orgId}/invoices")
@Authenticated
@Tag(name = "Invoices")
public class InvoiceResource {

    private final OrgAccess access;
    private final InvoiceService invoices;

    public InvoiceResource(OrgAccess access, InvoiceService invoices) {
        this.access = access;
        this.invoices = invoices;
    }

    @GET
    @Operation(summary = "The invoices and drafts of the organisation", description = "For owners and managers. Newest first.")
    public List<InvoiceSummary> list(@PathParam("orgId") long orgId) {
        access.manager(orgId);
        return invoices.list(orgId);
    }

    @POST
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Draft an invoice for a client and a period",
            description = "For owners and managers. From the billable time of approved weeks of the client's projects that is on no"
                    + " other invoice, priced entry by entry at the rates of today, one line per project and task, or per project"
                    + " and person. Projects with a money budget and no hourly rate are fixed price and left out, and leftOut says so."
                    + " Amounts are in cents.")
    @Fails(status = 400, code = "invalid_range", when = "the period ends before it starts or is longer than a year")
    @Fails(status = 409, code = "nothing_to_invoice", when = "there is no approved billable time of the client in the period that is not invoiced")
    public InvoiceResponse draft(@PathParam("orgId") long orgId, @Valid @NotNull DraftRequest request) {
        Membership me = access.manager(orgId);
        Client client = access.clientIn(orgId, request.clientId());
        return invoices.draft(me, client, request);
    }

    @GET
    @Path("/{invoiceId}")
    @Operation(summary = "An invoice with its lines", description = "For owners and managers. Amounts are in cents.")
    public InvoiceResponse get(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId) {
        Membership me = access.manager(orgId);
        return invoices.get(me, invoiceId);
    }

    @PATCH
    @Path("/{invoiceId}")
    @Transactional
    @Operation(summary = "Change the tax rate or the notes of a draft", description = "For owners and managers.")
    @Fails(status = 409, code = "not_draft", when = "the invoice is issued")
    public InvoiceResponse change(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId,
                                  @Valid @NotNull DraftChange change) {
        Membership me = access.manager(orgId);
        return invoices.change(me, invoiceId, change);
    }

    @DELETE
    @Path("/{invoiceId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Delete a draft", description = "For owners and managers. Its time is on no invoice again.")
    @Fails(status = 409, code = "not_draft", when = "the invoice is issued")
    public void delete(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId) {
        Membership me = access.manager(orgId);
        invoices.deleteDraft(me, invoiceId);
    }

    @PATCH
    @Path("/{invoiceId}/lines/{lineId}")
    @Transactional
    @Operation(summary = "Change the description of a line of a draft", description = "For owners and managers.")
    @Fails(status = 409, code = "not_draft", when = "the invoice is issued")
    public InvoiceResponse changeLine(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId,
                                      @PathParam("lineId") long lineId, @Valid @NotNull LineChange change) {
        Membership me = access.manager(orgId);
        return invoices.changeLine(me, invoiceId, lineId, change.description());
    }

    @DELETE
    @Path("/{invoiceId}/lines/{lineId}")
    @Transactional
    @Operation(summary = "Take a line off a draft", description = "For owners and managers. Its time is on no invoice again.")
    @Fails(status = 409, code = "not_draft", when = "the invoice is issued")
    public InvoiceResponse removeLine(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId,
                                      @PathParam("lineId") long lineId) {
        Membership me = access.manager(orgId);
        return invoices.removeLine(me, invoiceId, lineId);
    }

    @POST
    @Path("/{invoiceId}/issue")
    @Transactional
    @Operation(summary = "Issue a draft",
            description = "For owners and managers. It gets the next number of the organisation, without gaps, and never changes again.")
    @Fails(status = 409, code = "not_draft", when = "the invoice is issued already")
    @Fails(status = 409, code = "empty_invoice", when = "the draft has no lines")
    public InvoiceResponse issue(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId) {
        Membership me = access.manager(orgId);
        return invoices.issue(me, invoiceId);
    }

    @POST
    @Path("/{invoiceId}/credit")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Cancel an issued invoice with a credit note",
            description = "For owners and managers. The credit note has the same lines with the amounts negated and the next number,"
                    + " and the time of the invoice can be invoiced again.")
    @Fails(status = 409, code = "not_issued", when = "the invoice is a draft or a credit note")
    @Fails(status = 409, code = "already_credited", when = "the invoice has a credit note already")
    public InvoiceResponse credit(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId) {
        Membership me = access.manager(orgId);
        return invoices.credit(me, invoiceId);
    }

    @GET
    @Path("/{invoiceId}/lines.csv")
    @Produces("text/csv")
    @Operation(summary = "The lines of an invoice as CSV", description = "For owners and managers.")
    @APIResponse(responseCode = "200",
            description = "A header row and one row per line, as an attachment, with the amounts in the invoice's currency.",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = SchemaType.STRING)))
    public Response csv(@PathParam("orgId") long orgId, @PathParam("invoiceId") long invoiceId) {
        Membership me = access.manager(orgId);
        Invoice invoice = invoices.find(me, invoiceId, false);
        CsvWriter csv = new CsvWriter().row("number", "kind", "client", "line", "project", "task", "person", "description",
                "hours", "rate", "amount", "currency");
        for (InvoiceLine l : invoices.linesOf(invoice.id)) {
            csv.row(invoice.number == null ? "" : invoice.number, invoice.kind.name().toLowerCase(), invoice.clientName,
                    l.position + 1, l.projectName, l.taskTitle == null ? "" : l.taskTitle, l.personName == null ? "" : l.personName,
                    l.description, Money.hours(l.minutes), InvoiceMath.amount(l.rateCents), InvoiceMath.amount(l.amountCents),
                    invoice.currency);
        }
        String name = invoice.number == null ? "invoice-draft-" + invoice.id : "invoice-" + invoice.number;
        return Response.ok(csv.toString()).header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"").build();
    }
}
