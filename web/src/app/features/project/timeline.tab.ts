import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { messageOf } from '../../core/errors';
import { TaskChange } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate } from '../../shared/dates';
import { Gantt } from '../gantt/gantt';
import { Zoom } from '../gantt/gantt-layout';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';
import { MoveTo, issueLines, moveCost, planEndNote } from './schedule-issues';

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
      <label><input type="checkbox" [(ngModel)]="showCritical" /> Highlight critical path</label>
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
    @if (store.scheduleError(); as problem) {
      <p class="alert error" role="alert">
        The schedule could not be checked after the last change, so warnings are not shown: {{ problem }}
        <button type="button" class="link" (click)="store.refreshSchedule()">Try again</button>
      </p>
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
        Drag a bar to move it and tasks that depend on it move too. At day and week zoom, drag an end to change the start or
        due date, and drag the dot after a bar onto the task that should wait for it. Click an arrow to remove it. With the
        keyboard: the arrow keys move the focused bar by a working day, Shift with the arrow keys moves its due date, and
        Delete removes the focused arrow.
      </p>
    }
    @if (message()) {
      <p class="alert info" role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="card gantt-card">
      <app-gantt [tasks]="store.tasks()" [phases]="store.phases()" [critical]="store.criticalIds()"
        [broken]="store.brokenLinks()" [showCritical]="showCritical" [zoom]="zoom()" [editable]="org.isTeam()"
        (moved)="move($event.taskId, $event.days)" (resized)="resize($event)" (linked)="link($event)"
        (unlinked)="unlink($event)" (opened)="openTask($event)" />
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimelineTab {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  protected readonly openTask = injectOpenTask();

  protected readonly zooms: Zoom[] = ['day', 'week', 'month'];
  protected readonly zoom = signal<Zoom>('day');
  protected showCritical = true;
  protected readonly showIssues = signal(false);
  protected readonly lines = computed(() => issueLines(this.store.issues(), (id) => this.title(id)));
  protected readonly endNote = computed(() =>
    planEndNote(this.store.criticalPath()?.finish, this.store.tasks(), this.store.issues()),
  );
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly formatDate = formatDate;
  protected readonly moveCost = moveCost;

  /** A bar was dragged or moved with the arrow keys, by working days. */
  move(taskId: number, days: number): void {
    this.apply(this.store.shiftTask(taskId, days), (change) => pushedBack(change, `${change.task.title} moved`));
  }

  /** One end of a bar was dragged: only that date is sent. */
  resize(change: { taskId: number; startDate?: string; dueDate?: string }): void {
    const { taskId, ...dates } = change;
    this.apply(this.store.updateTask(taskId, dates), (done) => pushedBack(done, `${done.task.title} changed`));
  }

  /** A link was drawn on the timeline. It goes the way of the form in the task panel, loop check included. */
  link(link: { predecessorId: number; successorId: number }): void {
    this.apply(this.store.addDependency(link.successorId, link.predecessorId, 0), (change) => {
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
  private apply<T>(request: Observable<T>, describe: (result: T) => string | null): void {
    this.error.set(null);
    this.message.set(null);
    request.subscribe({
      next: (result) => this.message.set(describe(result)),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private title(taskId: number): string {
    return this.store.byId().get(taskId)?.title ?? `#${taskId}`;
  }
}

/** "X moved; 2 dependent tasks were pushed back.", or null when nothing else had to move. */
function pushedBack(change: TaskChange, what: string): string | null {
  const count = change.rescheduled.length;
  return count ? `${what}; ${count} dependent task${count === 1 ? ' was' : 's were'} pushed back.` : null;
}
