import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { TimesheetStatus, TimesheetSummary, Week } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { TimeApi } from '../../core/time.api';
import { dayName, formatDate } from '../../shared/dates';
import { TIMESHEET_LABELS, formatMinutes, formatMoney } from '../../shared/format';
import { buildGrid } from '../time/timesheet-grid';

@Component({
  selector: 'app-approvals-page',
  imports: [FormsModule],
  template: `
    <div class="page-header">
      <h1>Timesheet approvals</h1>
      <div class="segmented" role="group" aria-label="Status">
        @for (s of tabs; track s) {
          <button type="button" [class.active]="status() === s" (click)="setStatus(s)">{{ labels[s] }}</button>
        }
      </div>
    </div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="grid-2 wide-right">
      <section class="card">
        <ul class="sheet-list">
          @for (s of sheets(); track s.id) {
            <li>
              <button type="button" class="row-button" [class.selected]="selected()?.timesheetId === s.id" (click)="open(s)">
                <span class="grow"><strong>{{ s.userName }}</strong><br /><span class="muted small">Week of {{ formatDate(s.weekStart) }}</span></span>
                <span class="right">{{ formatMinutes(s.totalMinutes) }}</span>
              </button>
            </li>
          } @empty {
            <li class="muted">{{ status() === 'SUBMITTED' ? 'Nothing waiting for approval.' : 'No timesheets.' }}</li>
          }
        </ul>
      </section>
      <section class="card">
        @if (selected(); as w) {
          <h2>{{ w.userName }} · week of {{ formatDate(w.weekStart) }}</h2>
          <p class="muted">{{ formatMinutes(w.totalMinutes) }} total, {{ formatMinutes(w.billableMinutes) }} billable · {{ labels[w.status] }}</p>
          @if (grid(); as g) {
            <div class="table-wrap">
              <table class="table timesheet-grid">
                <thead><tr><th>Project / task</th>
                  @for (d of g.dates; track d) { <th class="num">{{ dayName(d) }}</th> }
                  <th class="num">Total</th></tr></thead>
                <tbody>
                  @for (row of g.rows; track row.key) {
                    <tr>
                      <td><span class="code">{{ row.projectCode }}</span> {{ row.taskTitle || row.projectName }}</td>
                      @for (m of row.days; track $index) { <td class="num">{{ m ? formatMinutes(m) : '' }}</td> }
                      <td class="num">{{ formatMinutes(row.total) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
          @if (w.status === 'SUBMITTED') {
            <div class="review-actions">
              <label for="note">Note (sent with "send back")</label>
              <input id="note" [(ngModel)]="note" placeholder="What needs fixing?" />
              <div class="actions">
                <button type="button" (click)="reject()">Send back</button>
                <button type="button" class="primary" (click)="approve()">Approve</button>
              </div>
            </div>
          }
        } @else {
          <p class="muted">Choose a timesheet to review.</p>
        }
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsPage {
  private readonly api = inject(TimeApi);
  protected readonly org = inject(OrgContext);

  protected readonly tabs: TimesheetStatus[] = ['SUBMITTED', 'APPROVED', 'REJECTED'];
  protected readonly status = signal<TimesheetStatus>('SUBMITTED');
  protected readonly sheets = signal<TimesheetSummary[]>([]);
  protected readonly selected = signal<Week | null>(null);
  protected readonly grid = signal<ReturnType<typeof buildGrid> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected note = '';

  protected readonly labels = TIMESHEET_LABELS;
  protected readonly formatDate = formatDate;
  protected readonly formatMinutes = formatMinutes;
  protected readonly dayName = dayName;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      const status = this.status();
      if (orgId !== null) {
        this.api.timesheets(orgId, status).subscribe({
          next: (s) => this.sheets.set(s),
          error: (err: unknown) => this.error.set(messageOf(err)),
        });
      }
    });
  }

  money(amount: number): string {
    return formatMoney(amount, this.org.currency());
  }

  setStatus(status: TimesheetStatus): void {
    this.selected.set(null);
    this.status.set(status);
  }

  open(sheet: TimesheetSummary): void {
    this.note = '';
    this.api.timesheet(this.org.id(), sheet.id).subscribe({
      next: (w) => this.show(w),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  approve(): void {
    this.review(false);
  }

  reject(): void {
    this.review(true);
  }

  private review(reject: boolean): void {
    const w = this.selected();
    if (!w?.timesheetId) {
      return;
    }
    const orgId = this.org.id();
    const request = reject ? this.api.reject(orgId, w.timesheetId, this.note) : this.api.approve(orgId, w.timesheetId);
    request.subscribe({
      next: (week) => {
        this.show(week);
        this.sheets.update((all) => all.filter((s) => s.id !== w.timesheetId));
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private show(week: Week): void {
    this.selected.set(week);
    this.grid.set(buildGrid(week.weekStart, week.entries));
  }
}
