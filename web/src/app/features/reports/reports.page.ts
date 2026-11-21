import { ChangeDetectionStrategy, Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { Member, ProjectOption, ReportGroupBy, ReportPreset, TimeReport, TimeReportRow, UtilisationReport } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi } from '../../core/project.api';
import { ReportApi, ReportRange, TimeReportQuery } from '../../core/report.api';
import { TimeApi } from '../../core/time.api';
import { addDays, formatDate, today, weekStart } from '../../shared/dates';
import { formatMinutes } from '../../shared/format';
import { ProgressBar } from '../../shared/progress-bar';
import { GROUPS, PRESETS, barWidth, capacityNote, csvName, formatAmount, formatShare } from './report-view';

type Tab = 'summary' | 'utilisation' | 'export';

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
  imports: [FormsModule, ProgressBar],
  templateUrl: './reports.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsPage {
  private readonly timeApi = inject(TimeApi);
  private readonly reportApi = inject(ReportApi);
  private readonly projectApi = inject(ProjectApi);
  private readonly orgApi = inject(OrgApi);
  protected readonly org = inject(OrgContext);

  /** Utilisation compares people, so it is for owners and managers. */
  protected readonly tabs = computed<{ id: Tab; label: string }[]>(() => [
    { id: 'summary', label: 'Summary' },
    ...(this.org.canManage() ? [{ id: 'utilisation' as const, label: 'Utilisation' }] : []),
    { id: 'export', label: 'Export' },
  ]);
  protected readonly tab = signal<Tab>('summary');

  // --- summary and utilisation ----------------------------------------------------------

  /** The range of both reports. A preset is turned into dates by the API, from the organisation's date. */
  protected readonly range = signal<ReportRange>({ preset: 'this_month' });
  protected readonly preset = computed<ReportPreset | null>(() => {
    const range = this.range();
    return 'preset' in range ? range.preset : null;
  });
  protected readonly groupBy = signal<ReportGroupBy>('PROJECT');
  /** Only time of approved weeks, on the summary: what can be invoiced. */
  protected readonly approvedOnly = signal(false);
  /** The same for the export of entries. */
  protected exportApprovedOnly = false;
  /**
   * A project whose tasks are shown, after its row was clicked. It belongs to the
   * organisation it was clicked in, like the reports below: all three start empty again
   * when the organisation changes, since the switcher changes it without leaving the page.
   */
  protected readonly drill = linkedSignal<number | null, { projectId: number; label: string } | null>({
    source: this.org.orgId,
    computation: () => null,
  });
  /** What the summary asks for: the tasks of one project after a click on its row, otherwise the chosen grouping. */
  private readonly query = computed<TimeReportQuery>(() => {
    const drill = this.drill();
    return { range: this.range(), groupBy: drill ? 'TASK' : this.groupBy(), projectId: drill?.projectId ?? null,
      approvedOnly: this.approvedOnly() };
  });
  protected readonly summary = linkedSignal<number | null, TimeReport | null>({ source: this.org.orgId, computation: () => null });
  protected readonly utilisation = linkedSignal<number | null, UtilisationReport | null>({
    source: this.org.orgId,
    computation: () => null,
  });
  protected readonly reportError = signal<string | null>(null);
  /** The report on screen, of whichever tab. */
  protected readonly shown = computed(() => (this.tab() === 'summary' ? this.summary() : this.tab() === 'utilisation' ? this.utilisation() : null));
  protected readonly groupLabel = computed(() => GROUPS.find((g) => g.value === this.summary()?.groupBy)?.label ?? '');
  /** The dates in the two inputs: what the last answer covered, or what was typed. */
  protected from = '';
  protected to = '';
  private asked = 0;

  protected readonly presets = PRESETS;
  protected readonly groups = GROUPS;
  protected readonly barWidth = barWidth;
  protected readonly capacityNote = capacityNote;
  protected readonly formatShare = formatShare;
  protected readonly formatMinutes = formatMinutes;
  protected readonly formatDate = formatDate;

  // --- export ---------------------------------------------------------------------------

  protected readonly members = signal<Member[]>([]);
  protected readonly projects = signal<ProjectOption[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected exportFrom = addDays(weekStart(today()), -7);
  protected exportTo = addDays(weekStart(today()), -1);
  protected userId: number | null = null;
  protected projectId: number | null = null;
  protected billable: boolean | null = null;

  constructor() {
    // The export form's choices are a person and a project of one organisation.
    effect(() => {
      this.org.orgId();
      untracked(() => {
        this.userId = null;
        this.projectId = null;
        this.members.set([]);
        this.projects.set([]);
      });
    });
    // The lists for the export form, once that tab is opened.
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId === null || this.tab() !== 'export') {
        return;
      }
      this.projectApi.options(orgId).subscribe({ next: (p) => this.projects.set(p) });
      this.orgApi.members(orgId).subscribe({ next: (m) => this.members.set(m.filter((x) => x.role !== 'CLIENT')) });
    });
    effect(() => {
      const orgId = this.org.orgId();
      const tab = this.tab();
      const range = this.range();
      const query = this.query();
      if (orgId === null || tab === 'export') {
        return;
      }
      untracked(() => (tab === 'summary' ? this.loadSummary(orgId, query) : this.loadUtilisation(orgId, range)));
    });
    effect(() => {
      // Somebody who is not a manager in the organisation they switched to has no utilisation tab.
      if (!this.tabs().some((t) => t.id === this.tab())) {
        this.tab.set('summary');
      }
    });
  }

  money(amount: number | null | undefined): string {
    return formatAmount(amount, this.org.currency());
  }

  choosePreset(preset: ReportPreset): void {
    this.range.set({ preset });
  }

  /** The two date inputs: a range of one's own once both are filled in and run forwards. */
  chooseDates(): void {
    if (this.from && this.to && this.from <= this.to) {
      this.range.set({ from: this.from, to: this.to });
    } else if (this.from && this.to) {
      this.reportError.set('Choose a date range that runs forwards');
    }
  }

  regroup(groupBy: ReportGroupBy): void {
    this.drill.set(null);
    this.groupBy.set(groupBy);
  }

  /** From a project row to the tasks of that project. */
  openProject(row: TimeReportRow): void {
    this.drill.set({ projectId: Number(row.key), label: row.label });
  }

  allProjects(): void {
    this.drill.set(null);
    this.groupBy.set('PROJECT');
  }

  /** The rows on screen as CSV, asked for with the very same range and filters. */
  download(): void {
    const report = this.shown();
    if (!report) {
      return;
    }
    const orgId = this.org.id();
    const summary = this.tab() === 'summary';
    const request = summary ? this.reportApi.timeCsv(orgId, this.query()) : this.reportApi.utilisationCsv(orgId, this.range());
    const name = csvName(summary ? this.query().groupBy : 'utilisation', report.from, report.to);
    this.busy.set(true);
    this.reportError.set(null);
    request.subscribe({
      next: (blob) => {
        downloadBlob(blob, name);
        this.busy.set(false);
      },
      error: (err: unknown) => {
        this.reportError.set(messageOf(err));
        this.busy.set(false);
      },
    });
  }

  private loadSummary(orgId: number, query: TimeReportQuery): void {
    const asked = ++this.asked;
    this.reportError.set(null);
    this.reportApi.time(orgId, query).subscribe({
      next: (report) => {
        if (asked === this.asked) {
          this.summary.set(report);
          this.showDates(report);
        }
      },
      error: (err: unknown) => {
        if (this.failed(asked, err)) {
          this.summary.set(null);
        }
      },
    });
  }

  private loadUtilisation(orgId: number, range: ReportRange): void {
    const asked = ++this.asked;
    this.reportError.set(null);
    this.reportApi.utilisation(orgId, range).subscribe({
      next: (report) => {
        if (asked === this.asked) {
          this.utilisation.set(report);
          this.showDates(report);
        }
      },
      error: (err: unknown) => {
        if (this.failed(asked, err)) {
          this.utilisation.set(null);
        }
      },
    });
  }

  /** The dates a preset came to go into the inputs, so that they can be adjusted from there. */
  private showDates(report: { from: string; to: string }): void {
    this.from = report.from;
    this.to = report.to;
  }

  /**
   * Shows why the report could not be loaded. True when it was the one asked for last: the
   * caller then takes the rows off the screen, which belong to another question.
   */
  private failed(asked: number, err: unknown): boolean {
    if (asked === this.asked) {
      this.reportError.set(messageOf(err));
    }
    return asked === this.asked;
  }

  exportPreset(kind: 'lastWeek' | 'lastMonth' | 'thisYear'): void {
    const now = today();
    if (kind === 'lastWeek') {
      this.exportFrom = addDays(weekStart(now), -7);
      this.exportTo = addDays(weekStart(now), -1);
    } else if (kind === 'lastMonth') {
      const firstOfThisMonth = `${now.slice(0, 8)}01`;
      this.exportTo = addDays(firstOfThisMonth, -1);
      this.exportFrom = `${this.exportTo.slice(0, 8)}01`;
    } else {
      this.exportFrom = `${now.slice(0, 4)}-01-01`;
      this.exportTo = now;
    }
  }

  export(): void {
    if (!this.exportFrom || !this.exportTo || this.exportTo < this.exportFrom) {
      this.error.set('Choose a date range that runs forwards');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.timeApi
      .exportCsv(this.org.id(), { from: this.exportFrom, to: this.exportTo, userId: this.userId, projectId: this.projectId, billable: this.billable, approvedOnly: this.exportApprovedOnly })
      .subscribe({
        next: (blob) => {
          downloadBlob(blob, `timesheets-${this.exportFrom}-to-${this.exportTo}.csv`);
          this.busy.set(false);
        },
        error: (err: unknown) => {
          this.error.set(messageOf(err));
          this.busy.set(false);
        },
      });
  }
}
