import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { messageOf } from '../../core/errors';
import { Task, TimeEntry, Week } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi } from '../../core/project.api';
import { TaskApi } from '../../core/task.api';
import { TimeApi } from '../../core/time.api';
import { addDays, dayName, formatDate, today, weekStart } from '../../shared/dates';
import { TIMESHEET_LABELS, formatMinutes, parseDuration } from '../../shared/format';
import { buildGrid, weekOfParam } from './timesheet-grid';
import { TimerService } from './timer.service';

interface ProjectOption {
  id: number;
  label: string;
  billableByDefault: boolean;
}

@Component({
  selector: 'app-timesheet-page',
  imports: [FormsModule],
  templateUrl: './timesheet.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimesheetPage {
  private readonly api = inject(TimeApi);
  private readonly projectApi = inject(ProjectApi);
  private readonly taskApi = inject(TaskApi);
  protected readonly org = inject(OrgContext);
  protected readonly timer = inject(TimerService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  /** The week lives in ?week= so that a notification can link to the week it is about. */
  private readonly weekParam = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('week'))), {
    initialValue: this.route.snapshot.queryParamMap.get('week'),
  });
  protected readonly weekOf = computed(() => weekOfParam(this.weekParam(), today()));
  protected readonly week = signal<Week | null>(null);
  protected readonly projects = signal<ProjectOption[]>([]);
  protected readonly tasks = signal<Task[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly editing = signal<TimeEntry | null>(null);

  protected form = this.emptyForm();

  protected readonly grid = computed(() => {
    const w = this.week();
    return w ? buildGrid(w.weekStart, w.entries) : null;
  });
  protected readonly locked = computed(() => {
    const status = this.week()?.status;
    return status === 'SUBMITTED' || status === 'APPROVED';
  });
  protected readonly isCurrentWeek = computed(() => this.weekOf() === weekStart(today()));

  protected readonly labels = TIMESHEET_LABELS;
  protected readonly formatMinutes = formatMinutes;
  protected readonly formatDate = formatDate;
  protected readonly dayName = dayName;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.projectApi.list(orgId).subscribe({
          next: (items) =>
            this.projects.set(
              items
                .filter((i) => i.project.status !== 'DONE')
                .map((i) => ({ id: i.project.id, label: `${i.project.code} · ${i.project.name}`, billableByDefault: !!i.project.clientId })),
            ),
        });
      }
    });
    // A new entry belongs to the week on screen, also after moving to another week.
    effect(() => {
      const start = this.weekOf();
      if (!untracked(this.editing) && (this.form.date < start || this.form.date > addDays(start, 6))) {
        this.form.date = this.emptyForm().date;
      }
    });
    // Also reloads when a timer starts or stops elsewhere (top bar, task panel).
    effect(() => {
      this.timer.running();
      const orgId = this.org.orgId();
      const date = this.weekOf();
      if (orgId !== null) {
        this.load(orgId, date);
      }
    });
  }

  shiftWeek(weeks: number): void {
    this.showWeek(addDays(this.weekOf(), weeks * 7));
  }

  thisWeek(): void {
    this.showWeek(weekStart(today()));
  }

  /** The current week has no parameter, so a plain /time link keeps meaning "this week". */
  private showWeek(week: string): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { week: week === weekStart(today()) ? null : week },
      queryParamsHandling: 'merge',
    });
  }

  projectChanged(projectId: number | null): void {
    this.form.taskId = null;
    this.tasks.set([]);
    if (projectId) {
      const option = this.projects().find((p) => p.id === projectId);
      this.form.billable = option?.billableByDefault ?? true;
      this.taskApi.list(projectId).subscribe({ next: (t) => this.tasks.set(t.filter((x) => !x.milestone)) });
    }
  }

  edit(entry: TimeEntry): void {
    this.editing.set(entry);
    this.form = {
      projectId: entry.projectId,
      taskId: entry.taskId ?? null,
      date: entry.date,
      duration: formatMinutes(entry.minutes),
      description: entry.description ?? '',
      billable: entry.billable,
    };
    this.taskApi.list(entry.projectId).subscribe({ next: (t) => this.tasks.set(t.filter((x) => !x.milestone)) });
  }

  cancelEdit(): void {
    this.editing.set(null);
    this.form = this.emptyForm();
  }

  save(): void {
    this.formError.set(null);
    const minutes = parseDuration(this.form.duration);
    if (!this.form.projectId) {
      this.formError.set('Choose a project');
      return;
    }
    if (minutes === null || minutes <= 0 || minutes > 1440) {
      this.formError.set('Enter a duration like 1h 30m, 90m or 1.5');
      return;
    }
    const body = {
      projectId: this.form.projectId,
      taskId: this.form.taskId,
      date: this.form.date,
      minutes,
      description: this.form.description || null,
      billable: this.form.billable,
    };
    const orgId = this.org.id();
    const editing = this.editing();
    const request = editing ? this.api.update(orgId, editing.id, body) : this.api.create(orgId, body);
    request.subscribe({
      next: () => {
        const keep = { projectId: this.form.projectId, taskId: this.form.taskId, billable: this.form.billable };
        this.editing.set(null);
        this.form = { ...this.emptyForm(), ...keep, date: this.form.date };
        this.load(orgId, this.weekOf());
      },
      error: (err: unknown) => this.formError.set(messageOf(err)),
    });
  }

  remove(entry: TimeEntry): void {
    if (!confirm('Delete this time entry?')) {
      return;
    }
    const orgId = this.org.id();
    this.api.delete(orgId, entry.id).subscribe({
      next: () => this.load(orgId, this.weekOf()),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  submit(): void {
    const w = this.week();
    if (!w) {
      return;
    }
    this.api.submit(this.org.id(), w.weekStart).subscribe({
      next: (week) => this.week.set(week),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  withdraw(): void {
    const w = this.week();
    if (!w) {
      return;
    }
    this.api.withdraw(this.org.id(), w.weekStart).subscribe({
      next: (week) => this.week.set(week),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private load(orgId: number, date: string): void {
    this.error.set(null);
    this.api.week(orgId, date).subscribe({
      next: (w) => this.week.set(w),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private emptyForm() {
    const day = today();
    const start = this.weekOf();
    const inWeek = day >= start && day <= addDays(start, 6) ? day : start;
    return {
      projectId: null as number | null,
      taskId: null as number | null,
      date: inWeek,
      duration: '',
      description: '',
      billable: true,
    };
  }
}
