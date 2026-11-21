import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { InvoiceApi } from '../../core/invoice.api';
import { Client, InvoiceGrouping, InvoiceSummary } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { addDays, formatRange, today } from '../../shared/dates';
import { formatCents, invoiceTitle } from './invoice-text';

/** The invoices of the organisation, and a draft for a client and a period. For owners and managers. */
@Component({
  selector: 'app-invoices-page',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-header"><h1>Invoices</h1></div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="grid-2 wide-right">
      <section class="card">
        <h2>New draft</h2>
        <p class="muted small">From the billable time of approved weeks that is on no other invoice, at today's rates.</p>
        <form class="form-grid" (ngSubmit)="draft()">
          <div class="field span-2"><label for="i-client">Client</label>
            <select id="i-client" name="client" [(ngModel)]="clientId" required>
              @for (c of clients(); track c.id) { <option [ngValue]="c.id">{{ c.name }}</option> }
            </select></div>
          <div class="field"><label for="i-from">From</label><input id="i-from" name="from" type="date" [(ngModel)]="from" required /></div>
          <div class="field"><label for="i-to">To</label><input id="i-to" name="to" type="date" [(ngModel)]="to" required /></div>
          <div class="field"><label for="i-group">Lines</label>
            <select id="i-group" name="group" [(ngModel)]="groupBy">
              <option value="TASK">Per project and task</option>
              <option value="PERSON">Per project and person</option>
            </select></div>
          <div class="field"><label for="i-tax">Tax rate (%)</label>
            <input id="i-tax" name="tax" type="number" min="0" max="100" step="0.01" [(ngModel)]="taxRatePercent" /></div>
          <div class="actions span-2"><button type="submit" class="primary" [disabled]="!clientId || busy()">Make a draft</button></div>
        </form>
      </section>
      <section class="card">
        <table class="table">
          <thead><tr><th>Invoice</th><th>Client</th><th>Period</th><th class="num">Total</th></tr></thead>
          <tbody>
            @for (i of invoices(); track i.id) {
              <tr>
                <td><a [routerLink]="['/invoices', i.id]">{{ title(i) }}</a>
                  @if (i.creditedById) { <span class="muted small">cancelled</span> }</td>
                <td>{{ i.clientName }}</td>
                <td class="nowrap">{{ formatRange(i.periodFrom, i.periodTo) }}</td>
                <td class="num">{{ money(i.totalCents, i.currency) }}</td>
              </tr>
            } @empty {
              <tr><td colspan="4" class="muted">No invoices yet.</td></tr>
            }
          </tbody>
        </table>
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvoicesPage {
  private readonly api = inject(InvoiceApi);
  private readonly orgApi = inject(OrgApi);
  private readonly router = inject(Router);
  protected readonly org = inject(OrgContext);

  protected readonly invoices = signal<InvoiceSummary[]>([]);
  protected readonly clients = signal<Client[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected clientId: number | null = null;
  /** Last month by default. */
  protected from = addDays(today().slice(0, 8) + '01', -1).slice(0, 8) + '01';
  protected to = addDays(today().slice(0, 8) + '01', -1);
  protected groupBy: InvoiceGrouping = 'TASK';
  protected taxRatePercent = 0;

  protected readonly title = invoiceTitle;
  protected readonly money = formatCents;
  protected readonly formatRange = formatRange;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId === null) {
        return;
      }
      this.api.list(orgId).subscribe({ next: (list) => this.invoices.set(list), error: (err: unknown) => this.error.set(messageOf(err)) });
      this.orgApi.clients(orgId).subscribe({ next: (c) => this.clients.set(c) });
    });
  }

  draft(): void {
    if (!this.clientId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.draft(this.org.id(), {
      clientId: this.clientId, from: this.from, to: this.to, groupBy: this.groupBy, taxRatePercent: this.taxRatePercent || 0,
    }).subscribe({
      next: (invoice) => {
        this.busy.set(false);
        void this.router.navigate(['/invoices', invoice.id]);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(messageOf(err));
      },
    });
  }
}
