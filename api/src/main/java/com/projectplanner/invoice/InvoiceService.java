package com.projectplanner.invoice;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.client.Client;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Money;
import com.projectplanner.invoice.InvoiceDtos.DraftChange;
import com.projectplanner.invoice.InvoiceDtos.DraftRequest;
import com.projectplanner.invoice.InvoiceDtos.InvoiceResponse;
import com.projectplanner.invoice.InvoiceDtos.InvoiceSummary;
import com.projectplanner.invoice.InvoiceDtos.LeftOut;
import com.projectplanner.invoice.InvoiceDtos.LineResponse;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.RateBook;
import com.projectplanner.project.Project;
import com.projectplanner.task.Task;
import com.projectplanner.time.ApprovedWeeks;
import com.projectplanner.time.TimeEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.projectplanner.common.Text.blankToNull;

/**
 * Invoices from approved, billable time.
 *
 * <p>Rows are locked in the order used everywhere: the organisation's first, then the
 * projects' by id, then their time entries'. An entry is taken under its lock and only while
 * it is on no line, so two drafts made at the same moment never take the same entry, and the
 * column it is linked by holds one line at most. Issuing takes the next number of the
 * organisation under the lock on its row, so the numbers have no gaps and no doubles.
 *
 * <p>A draft can be changed: its tax rate and notes, the descriptions of its lines, and lines
 * taken off, which releases their entries. An issued invoice never changes. A correction is a
 * credit note, issued at once with the next number, which cancels the invoice and releases
 * its entries so that they can be invoiced again.
 */
@ApplicationScoped
public class InvoiceService {

    /** The longest period one invoice covers. */
    static final int MAX_PERIOD_DAYS = 366;

    private final ActivityLog activity;
    private final Clock clock;

    public InvoiceService(ActivityLog activity, Clock clock) {
        this.activity = activity;
        this.clock = clock;
    }

    /** A project that does not fit an invoice built from hours: a money budget and no hourly rate. */
    static boolean fixedPrice(Project project) {
        return project.hourlyRate == null && project.budgetAmount != null;
    }

    public InvoiceResponse draft(Membership me, Client client, DraftRequest request) {
        if (request.to().isBefore(request.from()) || request.from().plusDays(MAX_PERIOD_DAYS).isBefore(request.to())) {
            throw ApiException.badRequest("invalid_range", "The period has to end on or after its start and be a year at most");
        }
        Organisation org = lockOrganisation(me.organisationId);
        List<Project> projects = Project.<Project>find("organisationId = ?1 and clientId = ?2 order by id", org.id, client.id)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list();
        List<LeftOut> leftOut = new ArrayList<>();
        Map<Long, Project> billable = new LinkedHashMap<>();
        for (Project project : projects) {
            if (fixedPrice(project)) {
                leftOut.add(new LeftOut(project.id, project.name, "fixed price: a money budget and no hourly rate"));
            } else {
                billable.put(project.id, project);
            }
        }
        ApprovedWeeks approved = ApprovedWeeks.of(org.id, request.from(), request.to());
        List<TimeEntry> entries = billable.isEmpty() ? List.of() : TimeEntry.<TimeEntry>find(
                        "organisationId = ?1 and projectId in ?2 and billable = true and invoiceLineId is null"
                                + " and entryDate between ?3 and ?4 and (startedAt is null or endedAt is not null) order by id",
                        org.id, billable.keySet(), request.from(), request.to())
                .withLock(LockModeType.PESSIMISTIC_WRITE).list().stream()
                .filter(e -> approved.approved(e.userId, e.entryDate))
                .toList();
        if (entries.isEmpty()) {
            throw ApiException.conflict("nothing_to_invoice",
                    "There is no approved billable time of " + client.name + " in that period that is not invoiced already");
        }

        Invoice invoice = new Invoice();
        invoice.organisationId = org.id;
        invoice.clientId = client.id;
        invoice.clientName = client.name;
        invoice.periodFrom = request.from();
        invoice.periodTo = request.to();
        invoice.currency = org.currency;
        invoice.groupBy = request.groupBy() == null ? InvoiceGrouping.TASK : request.groupBy();
        invoice.taxRateBp = request.taxRatePercent() == null ? 0 : InvoiceMath.basisPoints(request.taxRatePercent());
        invoice.notes = blankToNull(request.notes());
        invoice.createdAt = Instant.now(clock);
        invoice.persist();

        RateBook rates = RateBook.load(org.id);
        Map<Long, String> people = new HashMap<>();
        User.<User>list("id in ?1", entries.stream().map(e -> e.userId).collect(Collectors.toSet()))
                .forEach(u -> people.put(u.id, u.name));
        Map<Long, String> tasks = new HashMap<>();
        var taskIds = entries.stream().map(e -> e.taskId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!taskIds.isEmpty()) {
            Task.<Task>list("id in ?1", taskIds).forEach(t -> tasks.put(t.id, t.title));
        }

        // One line per project, task (or person) and rate. Each entry is priced on its own and
        // rounded to the cent, as the reports price it, so a line adds up to their amount.
        Map<String, InvoiceLine> lines = new LinkedHashMap<>();
        Map<InvoiceLine, List<TimeEntry>> entriesOf = new HashMap<>();
        for (TimeEntry e : entries) {
            Project project = billable.get(e.projectId);
            BigDecimal rate = rates.rate(project.hourlyRate, e.userId);
            long rateCents = InvoiceMath.cents(rate);
            boolean byTask = invoice.groupBy == InvoiceGrouping.TASK;
            String key = e.projectId + "|" + (byTask ? e.taskId : e.userId) + "|" + rateCents;
            InvoiceLine line = lines.computeIfAbsent(key, k -> {
                InvoiceLine l = new InvoiceLine();
                l.invoiceId = invoice.id;
                l.projectId = project.id;
                l.projectName = project.code + " · " + project.name;
                if (byTask) {
                    l.taskTitle = e.taskId == null ? null : tasks.get(e.taskId);
                    l.description = l.taskTitle == null ? "Other work" : l.taskTitle;
                } else {
                    l.personName = people.get(e.userId);
                    l.description = l.personName;
                }
                l.rateCents = rateCents;
                return l;
            });
            line.minutes += e.minutes;
            line.amountCents += InvoiceMath.cents(Money.cost(e.minutes, rate));
            entriesOf.computeIfAbsent(line, l -> new ArrayList<>()).add(e);
        }
        List<InvoiceLine> ordered = new ArrayList<>(lines.values());
        ordered.sort(Comparator.comparing((InvoiceLine l) -> l.projectName).thenComparing(l -> l.description));
        int position = 0;
        for (InvoiceLine line : ordered) {
            line.position = position++;
            line.persist();
            for (TimeEntry e : entriesOf.get(line)) {
                e.invoiceLineId = line.id;
            }
        }
        total(invoice, ordered);
        activity.record(org.id, null, null, me.userId, "invoice.drafted",
                "Drafted an invoice for " + client.name + " from " + invoice.periodFrom + " to " + invoice.periodTo);
        return response(invoice, ordered, leftOut);
    }

    public InvoiceResponse change(Membership me, long invoiceId, DraftChange change) {
        lockOrganisation(me.organisationId);
        Invoice invoice = draftOf(me, invoiceId);
        if (change.taxRatePercent() != null) {
            invoice.taxRateBp = InvoiceMath.basisPoints(change.taxRatePercent());
        }
        if (change.notes() != null) {
            invoice.notes = blankToNull(change.notes());
        }
        List<InvoiceLine> lines = linesOf(invoice.id);
        total(invoice, lines);
        return response(invoice, lines, List.of());
    }

    public InvoiceResponse changeLine(Membership me, long invoiceId, long lineId, String description) {
        lockOrganisation(me.organisationId);
        Invoice invoice = draftOf(me, invoiceId);
        lineOf(invoice, lineId).description = description.trim();
        return response(invoice, linesOf(invoice.id), List.of());
    }

    /** Takes a line off a draft. Its entries are on no invoice again. */
    public InvoiceResponse removeLine(Membership me, long invoiceId, long lineId) {
        lockOrganisation(me.organisationId);
        Invoice invoice = draftOf(me, invoiceId);
        InvoiceLine line = lineOf(invoice, lineId);
        TimeEntry.update("invoiceLineId = null where invoiceLineId = ?1", line.id);
        line.delete();
        List<InvoiceLine> lines = linesOf(invoice.id);
        total(invoice, lines);
        return response(invoice, lines, List.of());
    }

    /** Deletes a draft, which releases all its entries. */
    public void deleteDraft(Membership me, long invoiceId) {
        lockOrganisation(me.organisationId);
        Invoice invoice = draftOf(me, invoiceId);
        release(invoice.id);
        InvoiceLine.delete("invoiceId", invoice.id);
        invoice.delete();
    }

    /** Issues a draft with the next number of the organisation. From here on it never changes. */
    public InvoiceResponse issue(Membership me, long invoiceId) {
        Organisation org = lockOrganisation(me.organisationId);
        Invoice invoice = draftOf(me, invoiceId);
        List<InvoiceLine> lines = linesOf(invoice.id);
        if (lines.isEmpty()) {
            throw ApiException.conflict("empty_invoice", "A draft without lines can't be issued. Delete it instead.");
        }
        invoice.number = nextNumber(org.id);
        invoice.status = InvoiceStatus.ISSUED;
        invoice.issuedAt = Instant.now(clock);
        activity.record(org.id, null, null, me.userId, "invoice.issued",
                "Issued invoice " + invoice.number + " to " + invoice.clientName);
        return response(invoice, lines, List.of());
    }

    /**
     * Cancels an issued invoice with a credit note: the same lines with the amounts negated,
     * issued at once with the next number. The entries of the invoice are released, so that
     * the time can be invoiced again, correctly.
     */
    public InvoiceResponse credit(Membership me, long invoiceId) {
        Organisation org = lockOrganisation(me.organisationId);
        Invoice original = find(me, invoiceId, true);
        if (original.status != InvoiceStatus.ISSUED || original.kind != InvoiceKind.INVOICE) {
            throw ApiException.conflict("not_issued", "Only an issued invoice can be credited. A draft can be changed or deleted.");
        }
        if (Invoice.count("creditsInvoiceId", original.id) > 0) {
            throw ApiException.conflict("already_credited", "This invoice has a credit note already");
        }
        Invoice note = new Invoice();
        note.organisationId = org.id;
        note.clientId = original.clientId;
        note.clientName = original.clientName;
        note.kind = InvoiceKind.CREDIT_NOTE;
        note.creditsInvoiceId = original.id;
        note.periodFrom = original.periodFrom;
        note.periodTo = original.periodTo;
        note.currency = original.currency;
        note.groupBy = original.groupBy;
        note.taxRateBp = original.taxRateBp;
        note.notes = "Cancels invoice " + original.number;
        note.createdAt = Instant.now(clock);
        note.issuedAt = note.createdAt;
        note.status = InvoiceStatus.ISSUED;
        note.number = nextNumber(org.id);
        note.persist();
        List<InvoiceLine> lines = new ArrayList<>();
        for (InvoiceLine l : linesOf(original.id)) {
            InvoiceLine copy = new InvoiceLine();
            copy.invoiceId = note.id;
            copy.position = l.position;
            copy.projectId = l.projectId;
            copy.projectName = l.projectName;
            copy.taskTitle = l.taskTitle;
            copy.personName = l.personName;
            copy.description = l.description;
            copy.minutes = l.minutes;
            copy.rateCents = l.rateCents;
            copy.amountCents = -l.amountCents;
            copy.persist();
            lines.add(copy);
        }
        // The credit note mirrors the invoice cent for cent, its tax included.
        note.subtotalCents = -original.subtotalCents;
        note.taxCents = -original.taxCents;
        note.totalCents = -original.totalCents;
        release(original.id);
        activity.record(org.id, null, null, me.userId, "invoice.credited",
                "Credited invoice " + original.number + " to " + original.clientName + " with credit note " + note.number);
        return response(note, lines, List.of());
    }

    public InvoiceResponse get(Membership me, long invoiceId) {
        Invoice invoice = find(me, invoiceId, false);
        return response(invoice, linesOf(invoice.id), List.of());
    }

    public List<InvoiceSummary> list(long organisationId) {
        List<Invoice> invoices = Invoice.list("organisationId = ?1 order by createdAt desc, id desc", organisationId);
        Map<Long, Long> creditedBy = new HashMap<>();
        invoices.stream().filter(i -> i.creditsInvoiceId != null).forEach(i -> creditedBy.put(i.creditsInvoiceId, i.id));
        return invoices.stream().map(i -> new InvoiceSummary(i.id, i.kind, i.status, i.number, i.clientId, i.clientName,
                i.periodFrom, i.periodTo, i.currency, i.totalCents, i.issuedAt, i.creditsInvoiceId, creditedBy.get(i.id))).toList();
    }

    public List<InvoiceLine> linesOf(long invoiceId) {
        return InvoiceLine.list("invoiceId = ?1 order by position, id", invoiceId);
    }

    public Invoice find(Membership me, long invoiceId, boolean lock) {
        Invoice invoice;
        if (lock) {
            invoice = Invoice.findById(invoiceId, LockModeType.PESSIMISTIC_WRITE);
        } else {
            invoice = Invoice.findById(invoiceId);
        }
        if (invoice == null || !Objects.equals(invoice.organisationId, me.organisationId)) {
            throw ApiException.notFound("Invoice");
        }
        return invoice;
    }

    private Invoice draftOf(Membership me, long invoiceId) {
        Invoice invoice = find(me, invoiceId, true);
        if (invoice.status != InvoiceStatus.DRAFT) {
            throw ApiException.conflict("not_draft", "An issued invoice never changes. Correct it with a credit note.");
        }
        return invoice;
    }

    private static InvoiceLine lineOf(Invoice invoice, long lineId) {
        InvoiceLine line = InvoiceLine.findById(lineId);
        if (line == null || !Objects.equals(line.invoiceId, invoice.id)) {
            throw ApiException.notFound("Line");
        }
        return line;
    }

    private static Organisation lockOrganisation(long organisationId) {
        return Organisation.findById(organisationId, LockModeType.PESSIMISTIC_WRITE);
    }

    /** The next number of the organisation. Only call it with the organisation's row locked. */
    private static int nextNumber(long organisationId) {
        Integer highest = Invoice.getEntityManager()
                .createQuery("select max(i.number) from Invoice i where i.organisationId = :org", Integer.class)
                .setParameter("org", organisationId).getSingleResult();
        return highest == null ? 1 : highest + 1;
    }

    /** The entries of an invoice's lines are on no invoice again. */
    private static void release(long invoiceId) {
        TimeEntry.update("invoiceLineId = null where invoiceLineId in (select l.id from InvoiceLine l where l.invoiceId = ?1)", invoiceId);
    }

    private static void total(Invoice invoice, List<InvoiceLine> lines) {
        invoice.subtotalCents = lines.stream().mapToLong(l -> l.amountCents).sum();
        invoice.taxCents = InvoiceMath.tax(invoice.subtotalCents, invoice.taxRateBp);
        invoice.totalCents = invoice.subtotalCents + invoice.taxCents;
    }

    InvoiceResponse response(Invoice i, List<InvoiceLine> lines, List<LeftOut> leftOut) {
        Invoice credit = i.kind == InvoiceKind.INVOICE ? Invoice.<Invoice>find("creditsInvoiceId", i.id).firstResult() : null;
        return new InvoiceResponse(i.id, i.kind, i.status, i.number, i.clientId, i.clientName, i.periodFrom, i.periodTo, i.currency,
                i.groupBy, BigDecimal.valueOf(i.taxRateBp, 2), i.subtotalCents, i.taxCents, i.totalCents, i.notes, i.createdAt,
                i.issuedAt, i.creditsInvoiceId, credit == null ? null : credit.id,
                lines.stream().map(LineResponse::of).toList(), leftOut.isEmpty() ? null : leftOut);
    }
}
