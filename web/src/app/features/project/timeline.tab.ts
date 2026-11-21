import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Announcer } from '../../core/announcer';
import { messageOf } from '../../core/errors';
import { DependencyType, TaskChange } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { TaskPatch } from '../../core/task.api';
import { formatDate, formatRange } from '../../shared/dates';
import { Gantt } from '../gantt/gantt';
import { resizeDates } from '../gantt/gantt-edit';
import { Zoom } from '../gantt/gantt-layout';
import { baselineDates, baselineLabels, changedItems, countsText, endText } from './baseline-text';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';
import { MoveTo, criticalNote, issueLines, moveCost, planEndNote } from './schedule-issues';

@Component({
  selector: 'app-timeline-tab',
  imports: [FormsModule, Gantt],
  template: `
    <div class="toolbar">
      <div class="segmented" role="group" aria-label="Zoom">
        @for (z of zooms; track z) {
          <button type="button" [class.active]="zoom() === z" (click)="zoom.set(z)">{{ z }}</button>
        }
      </div>
      <label title="The tasks that move the end of the plan when they slip. A finished task stays where it was done and is never critical.">
        <input type="checkbox" [(ngModel)]="showCritical" /> Highlight critical path
      </label>
      @if (store.baselines().length) {
        <label>
          Compare with
          <select [ngModel]="store.comparedId()" (ngModelChange)="store.compareWith($event)">
            <option [ngValue]="null">No baseline</option>
            @for (b of store.baselines(); track b.id) {
              <option [ngValue]="b.id">{{ b.name }}{{ b.reference ? ' (reference)' : '' }}</option>
            }
          </select>
        </label>
      }
      @if (lines().length; as count) {
        <button type="button" class="link danger" [attr.aria-expanded]="showIssues()" (click)="showIssues.set(!showIssues())">
          {{ count }} schedule issue{{ count === 1 ? '' : 's' }}
        </button>
      }
      <span class="spacer"></span>
      @if (store.criticalPath(); as cp) {
        @if (cp.finish) {
          <span class="muted small">Plan ends {{ formatDate(cp.finish) }} · {{ cp.durationDays }} working days</span>
        }
      }
    </div>
    @if (endNote(); as note) {
      <p class="muted small plan-end-note">{{ note }}</p>
    }
    @if (criticalNote(store.criticalPath()); as note) {
      <p class="muted small plan-end-note">{{ note }}</p>
    }
    @if (store.scheduleError(); as problem) {
      <p class="alert error" role="alert">
        The schedule could not be checked after the last change, so warnings are not shown: {{ problem }}
        <button type="button" class="link" (click)="store.refreshSchedule()">Try again</button>
      </p>
    }
    @if (store.comparisonError(); as problem) {
      <p class="alert error" role="alert">
        The comparison with the baseline could not be fetched, so the baseline bars are not shown: {{ problem }}
        <button type="button" class="link" (click)="store.refreshComparison()">Try again</button>
      </p>
    }
    @if (store.comparison(); as cmp) {
      <p class="muted small baseline-summary">
        Grey bars: {{ cmp.baseline.name }}. {{ countsText(cmp.counts) }}. {{ endText(cmp.planEnd, 'The plan ends') }}.
        <button type="button" class="link" [attr.aria-expanded]="showChanges()" (click)="showChanges.set(!showChanges())">
          {{ showChanges() ? 'Hide' : 'List' }} the changes
        </button>
      </p>
      @if (showChanges()) {
        <section class="card issues-card" aria-label="Changes since the baseline">
          @if (changes().length) {
            <ul class="task-lines">
              @for (item of changes(); track item.key) {
                <li>
                  @if (item.kind === 'task' && !item.gone) {
                    <button type="button" class="link" (click)="openTask(item.id)">{{ item.name }}</button>
                  } @else {
                    <span>{{ item.name }}</span>
                  }
                  <span class="grow baseline-change">{{ item.text }}</span>
                </li>
              }
            </ul>
          } @else {
            <p class="muted">Every task and phase is where it was in {{ cmp.baseline.name }}.</p>
          }
        </section>
      }
    }
    @if (showIssues() && lines().length) {
      <section class="card issues-card" aria-label="Schedule issues">
        <ul class="task-lines">
          @for (line of lines(); track line.key) {
            <li>
              <button type="button" class="link" (click)="openTask(line.taskId)">{{ line.title }}</button>
              <span class="grow issue-note">{{ line.text }}</span>
              @if (org.isTeam() && line.fix; as fix) {
                @if (moveCost(fix); as cost) {
                  <span class="muted small">{{ cost }}</span>
                }
                <button type="button" class="small" (click)="applyFix(fix)">Move to {{ formatDate(fix.startDate) }}</button>
              }
            </li>
          }
        </ul>
      </section>
    }
    @if (org.isTeam()) {
      <p class="muted small">
        At day and week zoom: drag a bar to move it, and tasks that depend on it move too. Drag an end to change the start or
        due date, and drag the dot after a bar onto the task that should wait for it, then pick how it waits. Drag across the
        empty row of a task without dates to give it some. Near an edge the timeline scrolls along. Click an arrow to remove it.
        With the keyboard: the arrow keys move the focused bar by a working day, Shift with the arrow keys moves its due date and
        Alt its start date. L starts a link from the focused bar, the arrow keys pick the task that waits for it and Enter draws
        it. Delete removes the focused arrow and Escape gives up a drag or a link.
      </p>
    }
    @if (message()) {
      <!-- Shown here and read out once through the shell's live region, not as a region of its own. -->
      <p class="alert info">{{ message() }}</p>
    }
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="card gantt-card">
      <app-gantt [tasks]="store.tasks()" [phases]="store.phases()" [critical]="store.criticalIds()"
        [broken]="store.brokenLinks()" [baseline]="baselineBars()" [baselineLabels]="baselineTexts()" [showCritical]="showCritical" [zoom]="zoom()" [editable]="org.isTeam()" [holidays]="store.holidays()"
        (moved)="move($event.taskId, $event.days)" (resized)="resize($event)" (extended)="extend($event.taskId, $event.days)" (started)="start($event.taskId, $event.days)"
        (linked)="link($event)" (unlinked)="unlink($event)" (opened)="openTask($event)" />
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimelineTab {
  protected readonly store = inject(ProjectStore);
  private readonly announcer = inject(Announcer);
  protected readonly org = inject(OrgContext);
  protected readonly openTask = injectOpenTask();

  protected readonly zooms: Zoom[] = ['day', 'week', 'month'];
  protected readonly zoom = signal<Zoom>('day');
  protected showCritical = true;
  protected readonly showIssues = signal(false);
  protected readonly lines = computed(() => issueLines(this.store.issues(), (id) => this.title(id)));
  protected readonly criticalNote = criticalNote;
  protected readonly endNote = computed(() =>
    planEndNote(this.store.criticalPath()?.finish, this.store.tasks(), this.store.issues()),
  );
  protected readonly showChanges = signal(false);
  protected readonly baselineBars = computed(() => baselineDates(this.store.comparison()));
  protected readonly baselineTexts = computed(() => baselineLabels(this.store.comparison()));
  protected readonly changes = computed(() => changedItems(this.store.comparison()));
  protected readonly countsText = countsText;
  protected readonly endText = endText;
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly formatDate = formatDate;
  protected readonly moveCost = moveCost;

  /** Resizes on their way, by task: what to send next, and the due date the keys last asked for. */
  private readonly resizes = new Map<number, { next: TaskPatch | null; dueDate?: string; startDate?: string }>();

  /** Moves asked for and not sent yet, by task: the working days so far and the timer that sends them. */
  private readonly moves = new Map<number, { days: number; timer: ReturnType<typeof setTimeout> }>();

  /**
   * A bar was dragged or moved with the arrow keys, by working days. Presses that follow
   * each other within {@link MOVE_PAUSE_MS}, a held key included, are added up and sent
   * as one move, which is saved and read out once.
   */
  move(taskId: number, days: number): void {
    const waiting = this.moves.get(taskId);
    if (waiting) {
      clearTimeout(waiting.timer);
    }
    const total = (waiting?.days ?? 0) + days;
    const timer = setTimeout(() => {
      this.moves.delete(taskId);
      if (total !== 0) {
        this.apply(this.store.shiftTask(taskId, total), (change) => pushedBack(change, `${change.task.title} moved`));
      }
    }, MOVE_PAUSE_MS);
    this.moves.set(taskId, { days: total, timer });
  }

  /**
   * One end of a bar was dragged: only that date is sent. One request per task at a
   * time. What is asked for while it is on its way is kept, and the latest of it goes
   * out when the answer is in, so two answers cannot arrive in the wrong order.
   */
  resize(change: { taskId: number; startDate?: string; dueDate?: string }): void {
    const { taskId, ...dates } = change;
    const waiting = this.resizes.get(taskId);
    if (waiting) {
      waiting.next = { ...waiting.next, ...dates };
    } else {
      this.sendResize(taskId, dates);
    }
  }

  /**
   * Shift with an arrow key: the due date goes one working day on or back per press.
   * A press counts from the date the last press asked for, not from the stored one,
   * which only changes when the API has answered. Otherwise three quick presses
   * would ask for the same day three times.
   */
  extend(taskId: number, days: number): void {
    const task = this.store.byId().get(taskId);
    if (!task) {
      return;
    }
    const asked = this.resizes.get(taskId)?.dueDate;
    const dates = resizeDates({ startDate: task.startDate, dueDate: asked ?? task.dueDate }, 'end', days, this.store.holidays());
    if (dates && 'dueDate' in dates) {
      this.resize({ taskId, ...dates });
      const waiting = this.resizes.get(taskId);
      if (waiting) {
        waiting.dueDate = dates.dueDate;
      }
    }
  }

  /**
   * Alt with an arrow key: the start date goes one working day on or back per press,
   * counted from the start the last press asked for, as the due date does with Shift.
   */
  start(taskId: number, days: number): void {
    const task = this.store.byId().get(taskId);
    if (!task) {
      return;
    }
    const asked = this.resizes.get(taskId)?.startDate;
    const dates = resizeDates({ startDate: asked ?? task.startDate, dueDate: task.dueDate }, 'start', days, this.store.holidays());
    if (dates && 'startDate' in dates) {
      this.resize({ taskId, ...dates });
      const waiting = this.resizes.get(taskId);
      if (waiting) {
        waiting.startDate = dates.startDate;
      }
    }
  }

  private sendResize(taskId: number, dates: TaskPatch): void {
    const waiting = this.resizes.get(taskId) ?? { next: null };
    waiting.next = null;
    this.resizes.set(taskId, waiting);
    this.apply(
      this.store.updateTask(taskId, dates),
      (done) => pushedBack(done, `${done.task.title} changed`),
      () => {
        // Answered or refused: send what was asked for in the meantime, or forget the task.
        if (waiting.next) {
          this.sendResize(taskId, waiting.next);
        } else {
          this.resizes.delete(taskId);
        }
      },
      () => {
        // Refused: what the keys asked for since was counted from a date that was never saved.
        // It is dropped, and the next press counts from the dates the task really has.
        waiting.next = null;
        waiting.dueDate = undefined;
        waiting.startDate = undefined;
      },
    );
  }

  /** A link was drawn on the timeline, of the type picked after the drop. It goes the way of the form in the task panel, loop check included. */
  link(link: { predecessorId: number; successorId: number; type?: DependencyType }): void {
    this.apply(this.store.addDependency(link.successorId, link.predecessorId, 0, link.type ?? 'FS'), (change) => {
      const waits = `${change.task.title} now waits for ${this.title(link.predecessorId)}`;
      return pushedBack(change, waits) ?? `${waits}.`;
    });
  }

  unlink(link: { successorId: number; dependencyId: number }): void {
    const waited = this.store.byId().get(link.successorId)?.predecessors.find((d) => d.id === link.dependencyId);
    const text = waited ? `${this.title(link.successorId)} no longer waits for ${this.title(waited.predecessorId)}.` : null;
    this.apply(this.store.removeDependency(link.successorId, link.dependencyId), () => text);
  }

  /** Moves a task to the dates the API suggested for it, exactly as they came. */
  applyFix(fix: MoveTo): void {
    this.apply(
      this.store.updateTask(fix.taskId, { startDate: fix.startDate, dueDate: fix.dueDate }),
      (change) => pushedBack(change, `${change.task.title} moved`),
    );
  }

  /**
   * Sends one edit. The timeline draws what the store holds and the store only takes
   * what the API answered, so a refused edit leaves the bar where it was.
   */
  private apply<T>(request: Observable<T>, describe: (result: T) => string | null, after?: () => void, refused?: () => void): void {
    this.error.set(null);
    this.message.set(null);
    request.subscribe({
      next: (result) => {
        this.message.set(describe(result));
        // A screen reader hears every edit, a move with the arrow keys included, and where the task now is.
        this.announcer.announce(this.message() ?? landed(result));
        after?.();
      },
      error: (err: unknown) => {
        // The error paragraph is an alert, which is read out as it appears.
        this.error.set(messageOf(err));
        refused?.();
        after?.();
      },
    });
  }

  private title(taskId: number): string {
    return this.store.byId().get(taskId)?.title ?? `#${taskId}`;
  }
}

/** How long the arrow keys may pause between presses that still count as one move. */
export const MOVE_PAUSE_MS = 300;

/** "Design: 3 Mar – 7 Mar." for an answer about a task, or "Saved." for one that is not. */
function landed(result: unknown): string {
  const task = (result as Partial<TaskChange> | null)?.task;
  return task ? `${task.title}: ${formatRange(task.startDate, task.dueDate)}.` : 'Saved.';
}

/** "X moved; 2 dependent tasks were pushed back.", or null when nothing else had to move. */
function pushedBack(change: TaskChange, what: string): string | null {
  const count = change.rescheduled.length;
  return count ? `${what}; ${count} dependent task${count === 1 ? ' was' : 's were'} pushed back.` : null;
}
