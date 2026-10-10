import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { Member, ProjectListItem } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi } from '../../core/project.api';
import { TimeApi } from '../../core/time.api';
import { addDays, today, weekStart } from '../../shared/dates';

/** Saves a blob through a temporary link. */
export function downloadBlob(blob: Blob, filename: string, doc: Document = document): void {
  const url = URL.createObjectURL(blob);
  const link = doc.createElement('a');
  link.href = url;
  link.download = filename;
  doc.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

@Component({
  selector: 'app-reports-page',
  imports: [FormsModule],
  template: `
    <h1>Reports</h1>
    <section class="card narrow">
      <h2>Export timesheets</h2>
      <p class="muted small">
        One row per time entry as CSV, ready for invoicing or payroll.
        @if (org.canManage()) { Includes rates and amounts. } @else { Only your own time. }
      </p>
      <form class="form-grid" (ngSubmit)="export()">
        <div class="field">
          <label for="ex-from">From</label>
          <input id="ex-from" name="from" type="date" [(ngModel)]="from" required />
        </div>
        <div class="field">
          <label for="ex-to">To</label>
          <input id="ex-to" name="to" type="date" [(ngModel)]="to" required />
        </div>
        <div class="presets span-2">
          <button type="button" class="link small" (click)="preset('lastWeek')">Last week</button>
          <button type="button" class="link small" (click)="preset('lastMonth')">Last month</button>
          <button type="button" class="link small" (click)="preset('thisYear')">This year</button>
        </div>
        @if (org.canManage()) {
          <div class="field">
            <label for="ex-user">Person</label>
            <select id="ex-user" name="user" [(ngModel)]="userId">
              <option [ngValue]="null">Everyone</option>
              @for (m of members(); track m.userId) {
                <option [ngValue]="m.userId">{{ m.name }}</option>
              }
            </select>
          </div>
        }
        <div class="field">
          <label for="ex-project">Project</label>
          <select id="ex-project" name="project" [(ngModel)]="projectId">
            <option [ngValue]="null">All projects</option>
            @for (p of projects(); track p.project.id) {
              <option [ngValue]="p.project.id">{{ p.project.code }} · {{ p.project.name }}</option>
            }
          </select>
        </div>
        @if (error()) {
          <p class="alert error span-2">{{ error() }}</p>
        }
        <div class="actions span-2">
          <button type="submit" class="primary" [disabled]="busy()">{{ busy() ? 'Preparing…' : 'Download CSV' }}</button>
        </div>
      </form>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsPage {
  private readonly timeApi = inject(TimeApi);
  private readonly projectApi = inject(ProjectApi);
  private readonly orgApi = inject(OrgApi);
  protected readonly org = inject(OrgContext);

  protected readonly members = signal<Member[]>([]);
  protected readonly projects = signal<ProjectListItem[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected from = addDays(weekStart(today()), -7);
  protected to = addDays(weekStart(today()), -1);
  protected userId: number | null = null;
  protected projectId: number | null = null;
  protected billable: boolean | null = null;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId === null) {
        return;
      }
      this.projectApi.list(orgId).subscribe({ next: (p) => this.projects.set(p) });
      this.orgApi.members(orgId).subscribe({ next: (m) => this.members.set(m.filter((x) => x.role !== 'CLIENT')) });
    });
  }

  preset(kind: 'lastWeek' | 'lastMonth' | 'thisYear'): void {
    const now = today();
    if (kind === 'lastWeek') {
      this.from = addDays(weekStart(now), -7);
      this.to = addDays(weekStart(now), -1);
    } else if (kind === 'lastMonth') {
      const firstOfThisMonth = `${now.slice(0, 8)}01`;
      this.to = addDays(firstOfThisMonth, -1);
      this.from = `${this.to.slice(0, 8)}01`;
    } else {
      this.from = `${now.slice(0, 4)}-01-01`;
      this.to = now;
    }
  }

  export(): void {
    if (!this.from || !this.to || this.to < this.from) {
      this.error.set('Choose a date range that runs forwards');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.timeApi
      .exportCsv(this.org.id(), { from: this.from, to: this.to, userId: this.userId, projectId: this.projectId, billable: this.billable })
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `timesheets-${this.from}-to-${this.to}.csv`);
          this.busy.set(false);
        },
        error: (err: unknown) => {
          this.error.set(messageOf(err));
          this.busy.set(false);
        },
      });
  }
}
