import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  afterRenderEffect,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';
import { messageOf } from '../../core/errors';
import { Task, TimeEntry, Week } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi } from '../../core/project.api';
import { TaskApi } from '../../core/task.api';
import { TimeApi } from '../../core/time.api';
import { addDays, dayName, diffDays, formatDate, today, weekStart } from '../../shared/dates';
import { TIMESHEET_LABELS, formatMinutes, parseDuration } from '../../shared/format';
import { SaveQueue } from './save-queue';
import {
  ArrowKey,
  CellView,
  GridRow,
  RowRef,
  Typed,
  buildGrid,
  caretAtEdge,
  cellAction,
  cellEntries,
  cellKey,
  cellLabel,
  focusableCells,
  nextCell,
  parseCell,
  rowKey,
  viewRows,
  weekOfParam,
  withEntry,
  withoutEntry,
} from './timesheet-grid';
import { addRows, readRows, removeRow, rowsToCopy, toRowRef, writeRows } from './timesheet-rows';
import { TimerService } from './timer.service';

interface ProjectOption {
  id: number;
  code: string;
  name: string;
  label: string;
  billableByDefault: boolean;
}

const ARROWS: readonly string[] = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];

function without<V>(record: Record<string, V>, key: string): Record<string, V> {
  if (!(key in record)) {
    return record;
  }
  const copy = { ...record };
  delete copy[key];
  return copy;
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** The week lives in ?week= so that a notification can link to the week it is about. */
  private readonly weekParam = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('week'))), {
    initialValue: this.route.snapshot.queryParamMap.get('week'),
  });
  /** Null without ?week=: "this week", which the API works out from the organisation's date. */
  private readonly linkedWeek = computed(() => weekOfParam(this.weekParam()));
  /** The organisation's current week, once the API has said which one it is. */
  private readonly currentWeek = signal<string | null>(null);
  protected readonly weekOf = computed(() => this.linkedWeek() ?? this.currentWeek() ?? weekStart(today()));
  protected readonly week = signal<Week | null>(null);
  protected readonly projects = signal<ProjectOption[]>([]);
  protected readonly projectsLoaded = signal(false);
  protected readonly tasks = signal<Task[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly editing = signal<TimeEntry | null>(null);

  protected form = this.emptyForm();

  /** Rows shown although they have no time: added, copied from last week or emptied. Kept in localStorage. */
  private readonly rows = signal<RowRef[]>([]);
  /** What is being typed, per cell. A cell without a draft shows its saved time. */
  private readonly drafts = signal<Record<string, string>>({});
  /** Cells whose text can't be read, with the reason. Nothing is sent for those. */
  private readonly invalid = signal<Record<string, string>>({});
  private readonly saving = signal<Record<string, true>>({});
  protected readonly gridError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  /** The cell whose entries are pointed out in the list, after a cell with several entries was opened. */
  protected readonly shownCell = signal<string | null>(null);
  protected rowForm = { projectId: null as number | null, taskId: null as number | null };
  protected readonly rowTasks = signal<Task[]>([]);

  /** One save at a time per cell. The value it passes on is the cell's entries after a save. */
  private readonly queue = new SaveQueue<TimeEntry[]>();
  /** The text last sent off per cell, so that Enter followed by the blur it causes saves once. */
  private readonly asked = new Map<string, string>();
  /** The organisation the loaded week belongs to. */
  private weekOrg: number | null = null;
  private loads = 0;
  /** Counts changes made on this page, so that a reload that was asked for before one is not trusted. */
  private revision = 0;

  protected readonly grid = computed(() => {
    const w = this.week();
    return w ? buildGrid(w.weekStart, w.entries, this.locked() ? [] : this.rows()) : null;
  });
  protected readonly locked = computed(() => {
    const status = this.week()?.status;
    return status === 'SUBMITTED' || status === 'APPROVED';
  });
  protected readonly isCurrentWeek = computed(() => this.linkedWeek() === null || this.linkedWeek() === this.currentWeek());

  protected readonly view = computed(() => {
    const grid = this.grid();
    return grid ? viewRows(grid, { locked: this.locked(), drafts: this.drafts(), invalid: this.invalid(), saving: this.saving() }) : [];
  });
  /** What can't be saved as typed, one line per cell. */
  protected readonly problems = computed(() =>
    this.view()
      .flatMap((r) => r.cells)
      .filter((c) => c.invalid)
      .map((c) => ({ key: c.key, text: `${c.label}: ${c.invalid}` })),
  );

  protected readonly labels = TIMESHEET_LABELS;
  protected readonly formatMinutes = formatMinutes;
  protected readonly formatDate = formatDate;
  protected readonly dayName = dayName;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.projectApi.list(orgId).subscribe({
          next: (items) => {
            this.projects.set(
              items
                .filter((i) => i.project.status !== 'DONE')
                .map((i) => ({
                  id: i.project.id,
                  code: i.project.code,
                  name: i.project.name,
                  label: `${i.project.code} · ${i.project.name}`,
                  billableByDefault: !!i.project.clientId,
                })),
            );
            this.projectsLoaded.set(true);
          },
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
    // Angular writes an input's value only when its binding changes. Text that is typed and
    // dropped again before it was ever drawn (a refused change, the same time written another
    // way) leaves the binding where it was, and the cell would go on showing what was typed.
    afterRenderEffect(() => {
      this.view().forEach((row, rowIndex) => {
        for (const cell of row.cells) {
          const input = cell.control === 'input' ? this.cellElement(rowIndex, cell.day) : null;
          if (input instanceof HTMLInputElement && input.value !== cell.text) {
            input.value = cell.text;
          }
        }
      });
    });
    // Also reloads when a timer starts or stops elsewhere (top bar, task panel).
    effect(() => {
      this.timer.running();
      const orgId = this.org.orgId();
      const week = this.linkedWeek();
      if (orgId !== null) {
        untracked(() => this.load(orgId, week));
      }
    });
  }

  shiftWeek(weeks: number): void {
    this.showWeek(addDays(this.weekOf(), weeks * 7));
  }

  thisWeek(): void {
    this.showWeek(null);
  }

  /** The current week has no parameter, so a plain /time link keeps meaning "this week". */
  private showWeek(week: string | null): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { week: week === this.currentWeek() ? null : week },
      queryParamsHandling: 'merge',
    });
  }

  // --- the grid -------------------------------------------------------------------------

  selectAll(event: FocusEvent): void {
    (event.target as HTMLInputElement).select();
  }

  typed(cell: CellView, event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.drafts.update((d) => ({ ...d, [cell.key]: text }));
  }

  /**
   * Saves what was typed into a cell, when the cell is left or on Enter. False when the
   * text can't be read: nothing is sent, and the text stays in the cell to be corrected.
   */
  commit(row: GridRow, cell: CellView): boolean {
    const text = this.drafts()[cell.key];
    const week = this.week();
    const orgId = this.weekOrg;
    if (text === undefined || !week || orgId === null) {
      return true;
    }
    const typed = parseCell(text);
    if (typed.kind === 'invalid') {
      this.invalid.update((i) => ({ ...i, [cell.key]: typed.message }));
      return false;
    }
    this.invalid.update((i) => without(i, cell.key));
    if (this.asked.get(cell.key) === text) {
      return true;
    }
    this.asked.set(cell.key, text);
    this.gridError.set(null);
    const ref = toRowRef(row);
    const atCommit = cellEntries(week.entries, ref, cell.date);
    this.queue.submit(cell.key, (left) => this.saveCell(orgId, ref, cell.date, text, typed, left ?? atCommit));
    return true;
  }

  /**
   * The request for a cell, built when its turn comes. The decision is made against the
   * cell's entries at that moment: the week on screen if it still shows the cell, otherwise
   * what the save before this one left behind ({@code known}).
   */
  private saveCell(orgId: number, row: RowRef, date: string, text: string, typed: Typed, known: TimeEntry[]): Observable<TimeEntry[]> | null {
    const key = cellKey(row, date);
    const settle = () => {
      if (this.asked.get(key) === text) {
        this.asked.delete(key);
      }
      // Only what this save was sent for: something typed since then stays in the cell.
      this.drafts.update((d) => (d[key] === text ? without(d, key) : d));
      this.saving.update((s) => without(s, key));
    };
    const action = cellAction(this.onScreen(orgId, date) ? cellEntries(this.week()!.entries, row, date) : known, typed);
    if (action.kind === 'none' || action.kind === 'refuse') {
      if (action.kind === 'refuse') {
        this.gridError.set(`${cellLabel(row, date)}: ${action.message}`);
      }
      settle();
      return null;
    }
    const refused = (err: unknown) => {
      this.gridError.set(`${cellLabel(row, date)}: ${messageOf(err)}`);
      settle();
    };
    this.saving.update((s) => ({ ...s, [key]: true }));
    if (action.kind === 'delete') {
      return this.api.delete(orgId, action.entry.id).pipe(
        map((): TimeEntry[] => []),
        tap({
          next: () => {
            this.removeEntry(orgId, action.entry.id);
            if (this.onScreen(orgId, date) && !this.week()!.entries.some((e) => rowKey({ projectId: e.projectId, taskId: e.taskId ?? null }) === rowKey(row))) {
              // The row has just lost its last entry. Keep it, or it would vanish under the cursor.
              this.setRows(addRows(this.rows(), [row]));
            }
            settle();
          },
          error: refused,
        }),
      );
    }
    // A new entry leaves the billable flag to the server (client work yes, internal no). An
    // update replaces the whole entry, so the note and the flag are sent back as they are.
    const request =
      action.kind === 'create'
        ? this.api.create(orgId, { projectId: row.projectId, taskId: row.taskId, date, minutes: action.minutes })
        : this.api.update(orgId, action.entry.id, {
            projectId: row.projectId,
            taskId: row.taskId,
            date,
            minutes: action.minutes,
            description: action.entry.description ?? null,
            billable: action.entry.billable,
          });
    return request.pipe(
      tap({
        next: (entry) => {
          this.putEntry(orgId, entry);
          settle();
        },
        error: refused,
      }),
      map((entry) => [entry]),
    );
  }

  cellKeydown(event: KeyboardEvent, row: GridRow, cell: CellView, rowIndex: number): void {
    const target = event.target as HTMLElement;
    const input = target instanceof HTMLInputElement ? target : null;
    if (event.key === 'Enter' && input) {
      event.preventDefault();
      if (this.commit(row, cell)) {
        this.move(rowIndex, cell.day, 'ArrowDown');
      }
    } else if (event.key === 'Escape' && input) {
      this.drafts.update((d) => without(d, cell.key));
      this.invalid.update((i) => without(i, cell.key));
    } else if (ARROWS.includes(event.key)) {
      const key = event.key as ArrowKey;
      if (input && !caretAtEdge(key, input.value.length, input.selectionStart, input.selectionEnd)) {
        return;
      }
      event.preventDefault();
      this.move(rowIndex, cell.day, key);
    }
  }

  /** A cell with several entries is not typed into: point at its entries in the list instead. */
  showEntries(cell: CellView, list: HTMLElement): void {
    this.shownCell.set(cell.key);
    list.scrollIntoView?.({ block: 'start' });
    list.focus();
  }

  isShown(entry: TimeEntry): boolean {
    return cellKey({ projectId: entry.projectId, taskId: entry.taskId ?? null }, entry.date) === this.shownCell();
  }

  rowProjectChanged(projectId: number | null): void {
    this.rowForm.taskId = null;
    this.rowTasks.set([]);
    if (projectId) {
      this.taskApi.list(projectId).subscribe({ next: (t) => this.rowTasks.set(t.filter((x) => !x.milestone)) });
    }
  }

  addRow(): void {
    const project = this.projects().find((p) => p.id === this.rowForm.projectId);
    if (!project || !this.week()) {
      return;
    }
    const task = this.rowTasks().find((t) => t.id === this.rowForm.taskId) ?? null;
    const row: RowRef = {
      projectId: project.id,
      projectCode: project.code,
      projectName: project.name,
      taskId: task?.id ?? null,
      taskTitle: task?.title ?? null,
      billable: project.billableByDefault,
    };
    this.notice.set(null);
    this.setRows(addRows(this.rows(), [row]));
    this.rowForm = { projectId: project.id, taskId: null };
    // The row may have been there already. Either way, go to its first cell.
    afterNextRender(
      () => {
        const index = this.view().findIndex((r) => r.row.key === rowKey(row));
        this.cellElement(index, this.view()[index]?.cells.find((c) => c.control === 'input' || c.control === 'several')?.day ?? 0)?.focus();
      },
      { injector: this.injector },
    );
  }

  removeRow(row: GridRow): void {
    this.setRows(removeRow(this.rows(), row));
  }

  /** Adds last week's rows that are missing here, without their time. */
  copyLastWeek(): void {
    const week = this.week();
    const orgId = this.weekOrg;
    if (!week || orgId === null) {
      return;
    }
    this.gridError.set(null);
    this.api.week(orgId, addDays(week.weekStart, -7)).subscribe({
      next: (last) => {
        if (!this.onScreen(orgId, week.weekStart)) {
          return;
        }
        const open = new Set(this.projects().map((p) => p.id));
        const copied = rowsToCopy(buildGrid(last.weekStart, last.entries).rows, this.grid()?.rows ?? [], open);
        this.setRows(addRows(this.rows(), copied));
        this.notice.set(
          copied.length ? `Added ${copied.length} ${copied.length === 1 ? 'row' : 'rows'} from last week.` : 'Last week has no other rows to copy.',
        );
      },
      error: (err: unknown) => this.gridError.set(messageOf(err)),
    });
  }

  private move(rowIndex: number, day: number, key: ArrowKey): void {
    const target = nextCell(focusableCells(this.view()), { row: rowIndex, day }, key);
    if (target) {
      this.cellElement(target.row, target.day)?.focus();
    }
  }

  private cellElement(rowIndex: number, day: number): HTMLElement | null {
    return this.host.nativeElement.querySelector<HTMLElement>(`[data-cell="${rowIndex}:${day}"]`);
  }

  /** Whether the week on screen is the one that holds {@code date} for that organisation. */
  private onScreen(orgId: number, date: string): boolean {
    const week = this.week();
    if (!week || this.weekOrg !== orgId) {
      return false;
    }
    const day = diffDays(week.weekStart, date);
    return day >= 0 && day <= 6;
  }

  private setRows(rows: RowRef[]): void {
    const week = this.week();
    if (week && this.weekOrg !== null) {
      this.rows.set(rows);
      writeRows(localStorage, this.weekOrg, week.weekStart, rows);
    }
  }

  /** Puts a saved entry into the week on screen instead of loading the week again. */
  private putEntry(orgId: number, entry: TimeEntry): void {
    this.revision++;
    if (this.weekOrg === orgId) {
      this.week.update((w) => (w ? withEntry(w, entry) : w));
    }
  }

  private removeEntry(orgId: number, entryId: number): void {
    this.revision++;
    if (this.weekOrg === orgId) {
      this.week.update((w) => (w ? withoutEntry(w, entryId) : w));
    }
  }

  // --- the form and the list ------------------------------------------------------------

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
      next: (entry) => {
        const keep = { projectId: this.form.projectId, taskId: this.form.taskId, billable: this.form.billable };
        this.editing.set(null);
        this.form = { ...this.emptyForm(), ...keep, date: this.form.date };
        this.putEntry(orgId, entry);
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
      next: () => this.removeEntry(orgId, entry.id),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  submit(): void {
    const w = this.week();
    if (!w) {
      return;
    }
    const orgId = this.org.id();
    // A cell that was just left is still being saved. It belongs to the week that is submitted.
    this.queue.whenIdle(() =>
      this.api.submit(orgId, w.weekStart).subscribe({
        next: (week) => this.reviewed(orgId, week),
        error: (err: unknown) => this.error.set(messageOf(err)),
      }),
    );
  }

  withdraw(): void {
    const w = this.week();
    if (!w) {
      return;
    }
    const orgId = this.org.id();
    this.api.withdraw(orgId, w.weekStart).subscribe({
      next: (week) => this.reviewed(orgId, week),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  /** Loads a week; without one, the organisation's current week. */
  private load(orgId: number, week: string | null): void {
    const load = ++this.loads;
    const revision = this.revision;
    this.error.set(null);
    if (this.weekOrg !== orgId) {
      // Another organisation: its projects and entries have nothing to do with what is on screen.
      this.week.set(null);
    }
    this.api.week(orgId, week).subscribe({
      next: (w) => {
        if (load !== this.loads) {
          return;
        }
        if (revision !== this.revision) {
          // Something was saved here while this answer was on its way, and it may not know about it.
          this.load(orgId, week);
          return;
        }
        if (week === null) {
          this.currentWeek.set(w.weekStart);
        }
        this.show(orgId, w);
      },
      error: (err: unknown) => {
        if (load === this.loads) {
          this.error.set(messageOf(err));
        }
      },
    });
  }

  /** The week as it is after submitting or withdrawing it, unless another week is on screen by now. */
  private reviewed(orgId: number, week: Week): void {
    this.revision++;
    if (this.onScreen(orgId, week.weekStart)) {
      this.show(orgId, week);
    }
  }

  /** Puts a week from the API on screen, with the rows remembered for it. */
  private show(orgId: number, week: Week): void {
    const shown = this.week();
    if (this.weekOrg !== orgId || shown?.weekStart !== week.weekStart) {
      this.drafts.set({});
      this.invalid.set({});
      this.gridError.set(null);
      this.notice.set(null);
      this.shownCell.set(null);
    }
    this.weekOrg = orgId;
    this.rows.set(readRows(localStorage, orgId, week.weekStart));
    this.week.set(week);
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
