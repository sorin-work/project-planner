import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { messageOf } from '../../core/errors';
import { InvoiceApi } from '../../core/invoice.api';
import { Invoice, InvoiceLine } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate, formatRange } from '../../shared/dates';
import { downloadBlob } from '../reports/reports.page';
import { formatCents, invoiceActions, invoiceHours, invoiceTitle } from './invoice-text';

/**
 * One invoice. The paper part is what the browser prints, styled for paper by the print
 * rules in styles.css, which leave out everything else on the page. Every name on it is
 * text, never markup: the template interpolates and escapes it.
 */
@Component({
  selector: 'app-invoice-page',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-header no-print">
      <h1><a routerLink="/invoices">Invoices</a> / {{ heading() }}</h1>
      @if (invoice(); as i) {
        <div class="actions">
          <button type="button" (click)="print()">Print</button>
          <button type="button" (click)="csv()">CSV</button>
          @if (actions().issue) {
            <button type="button" class="danger" (click)="remove()">Delete draft</button>
            <button type="button" class="primary" [disabled]="busy() || !i.lines.length" (click)="issue()">Issue</button>
          }
          @if (actions().credit) {
            <button type="button" class="danger" [disabled]="busy()" (click)="credit()">Cancel with a credit note</button>
          }
        </div>
      }
    </div>
    @if (error()) {
      <p class="alert error no-print" role="alert">{{ error() }}</p>
    }
    @if (invoice(); as i) {
      @if (i.leftOut?.length) {
        <p class="alert info no-print" role="status">
          Left out: @for (p of i.leftOut; track p.projectId) { {{ p.projectName }} ({{ p.reason }}){{ $last ? '.' : ', ' }} }
        </p>
      }
      @if (i.creditedById) {
        <p class="alert info">Cancelled by <a [routerLink]="['/invoices', i.creditedById]">credit note {{ i.creditedByNumber }}</a>.</p>
      }
      @if (i.creditsInvoiceId) {
        <p class="alert info">Cancels <a [routerLink]="['/invoices', i.creditsInvoiceId]">invoice {{ i.creditsInvoiceNumber }}</a>.</p>
      }
      <article class="card invoice-paper">
        <header class="invoice-head">
          <div>
            <h2>{{ title(i) }}</h2>
            <p>To {{ i.clientName }}</p>
            <p class="muted small">For {{ formatRange(i.periodFrom, i.periodTo) }}
              @if (i.issuedAt) { · issued {{ formatDate(i.issuedAt.slice(0, 10)) }} }</p>
          </div>
          <div class="right">
            <span class="stat-value">{{ money(i.totalCents, i.currency) }}</span>
          </div>
        </header>
        <table class="table invoice-lines">
          <thead><tr><th>Project</th><th>Description</th><th class="num">Hours</th><th class="num">Rate</th><th class="num">Amount</th>
            @if (actions().edit) { <th class="no-print"><span class="sr-only">Actions</span></th> }</tr></thead>
          <tbody>
            @for (l of i.lines; track l.id) {
              <tr>
                <td>{{ l.projectName }}</td>
                <td>
                  @if (actions().edit) {
                    <input class="no-print" [attr.aria-label]="'Description of line ' + (l.position + 1)" [ngModel]="l.description"
                      (change)="describe(l, $any($event.target).value)" maxlength="500" />
                    <span class="print-only">{{ l.description }}</span>
                  } @else {
                    {{ l.description }}
                  }
                </td>
                <td class="num">{{ hours(l.minutes) }}</td>
                <td class="num">{{ money(l.rateCents, i.currency) }}</td>
                <td class="num">{{ money(l.amountCents, i.currency) }}</td>
                @if (actions().edit) {
                  <td class="no-print"><button type="button" class="small" (click)="removeLine(l)"
                    [attr.aria-label]="'Take line ' + (l.position + 1) + ' off'">Take off</button></td>
                }
              </tr>
            } @empty {
              <tr><td colspan="5" class="muted">No lines.</td></tr>
            }
          </tbody>
          <tfoot>
            <tr><th colspan="4" class="num">Subtotal</th><td class="num">{{ money(i.subtotalCents, i.currency) }}</td></tr>
            <tr><th colspan="4" class="num">Tax {{ i.taxRatePercent }}%</th><td class="num">{{ money(i.taxCents, i.currency) }}</td></tr>
            <tr><th colspan="4" class="num">Total</th><td class="num"><strong>{{ money(i.totalCents, i.currency) }}</strong></td></tr>
          </tfoot>
        </table>
        @if (i.notes && !actions().edit) {
          <p class="invoice-notes">{{ i.notes }}</p>
        }
      </article>
      @if (actions().edit) {
        <section class="card no-print">
          <h2>Draft</h2>
          <form class="form-grid" (ngSubmit)="save()">
            <div class="field"><label for="d-tax">Tax rate (%)</label>
              <input id="d-tax" name="tax" type="number" min="0" max="100" step="0.01" [(ngModel)]="taxRatePercent" /></div>
            <div class="field span-2"><label for="d-notes">Notes on the invoice</label>
              <textarea id="d-notes" name="notes" rows="3" maxlength="2000" [(ngModel)]="notes"></textarea></div>
            <div class="actions span-2"><button type="submit" [disabled]="busy()">Save</button></div>
          </form>
          <p class="muted small">Each line is its hours at its rate, rounded once to the cent. The time report rounds every
            entry, so an invoice can differ from it by a cent or so per line.</p>
          <p class="muted small">Issuing gives the invoice the next number. After that it never changes: a mistake is corrected
            with a credit note, and the time can then be invoiced again.</p>
        </section>
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicePage {
  private readonly api = inject(InvoiceApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly org = inject(OrgContext);

  private readonly id = toSignal(this.route.paramMap.pipe(map((p) => Number(p.get('invoiceId')))), { initialValue: NaN });
  protected readonly invoice = signal<Invoice | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly actions = computed(() => {
    const i = this.invoice();
    return i ? invoiceActions(i) : { edit: false, issue: false, credit: false };
  });
  protected readonly heading = computed(() => {
    const i = this.invoice();
    return i ? invoiceTitle(i) : '…';
  });
  protected taxRatePercent = 0;
  protected notes = '';

  protected readonly title = invoiceTitle;
  protected readonly money = formatCents;
  protected readonly hours = invoiceHours;
  protected readonly formatRange = formatRange;
  protected readonly formatDate = formatDate;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      const id = this.id();
      if (orgId === null || Number.isNaN(id)) {
        return;
      }
      this.invoice.set(null);
      this.api.get(orgId, id).subscribe({ next: (i) => this.show(i), error: (err: unknown) => this.error.set(messageOf(err)) });
    });
  }

  print(): void {
    window.print();
  }

  csv(): void {
    const i = this.invoice();
    if (!i) {
      return;
    }
    this.api.csv(this.org.id(), i.id).subscribe({
      next: (blob) => downloadBlob(blob, i.number ? `invoice-${i.number}.csv` : `invoice-draft-${i.id}.csv`),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  describe(line: InvoiceLine, description: string): void {
    const text = description.trim();
    if (!text || text === line.description) {
      return;
    }
    this.run((orgId, id) => this.api.changeLine(orgId, id, line.id, text));
  }

  removeLine(line: InvoiceLine): void {
    this.run((orgId, id) => this.api.removeLine(orgId, id, line.id));
  }

  save(): void {
    this.run((orgId, id) => this.api.change(orgId, id, { taxRatePercent: this.taxRatePercent || 0, notes: this.notes }));
  }

  issue(): void {
    if (confirm('Issue this invoice? It gets the next number and can no longer be changed.')) {
      this.run((orgId, id) => this.api.issue(orgId, id));
    }
  }

  credit(): void {
    if (!confirm('Cancel this invoice with a credit note? The credit note is issued at once.')) {
      return;
    }
    const i = this.invoice();
    if (!i) {
      return;
    }
    this.busy.set(true);
    this.api.credit(this.org.id(), i.id).subscribe({
      next: (note) => {
        this.busy.set(false);
        void this.router.navigate(['/invoices', note.id]);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(messageOf(err));
      },
    });
  }

  remove(): void {
    const i = this.invoice();
    if (!i || !confirm('Delete this draft? Its time can be invoiced again.')) {
      return;
    }
    this.api.delete(this.org.id(), i.id).subscribe({
      next: () => void this.router.navigate(['/invoices']),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private run(request: (orgId: number, id: number) => ReturnType<InvoiceApi['get']>): void {
    const i = this.invoice();
    if (!i || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    request(this.org.id(), i.id).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.show(updated);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(messageOf(err));
      },
    });
  }

  private show(invoice: Invoice): void {
    this.invoice.set(invoice);
    this.taxRatePercent = invoice.taxRatePercent;
    this.notes = invoice.notes ?? '';
  }
}
