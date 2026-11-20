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
import { MoveTo, issueLines, planEndNote } from './schedule-issues';

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
    @if (showIssues() && lines().length) {
      <section class="card issues-card" aria-label="Schedule issues">
        <ul class="task-lines">
          @for (line of lines(); track line.key) {
            <li>
              <button type="button" class="link" (click)="openTask(line.taskId)">{{ line.title }}</button>
              <span class="grow issue-note">{{ line.text }}</span>
              @if (org.isTeam() && line.fix; as fix) {
                <button type="button" class="small" (click)="applyFix(fix)">Move to {{ formatDate(fix.startDate) }}</button>
              }
            </li>
          }
        </ul>
      </section>
    }
    @if (org.isTeam()) {
      <p class="muted small">Drag a bar (or focus it and use the arrow keys) to move it; tasks that depend on it move too.</p>
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
        (moved)="move($event.taskId, $event.days)" (opened)="openTask($event)" />
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
  protected readonly lines = computed(() =>
    issueLines(this.store.issues(), (id) => this.store.byId().get(id)?.title ?? `#${id}`),
  );
  protected readonly endNote = computed(() =>
    planEndNote(this.store.criticalPath()?.finish, this.store.tasks(), this.store.issues()),
  );
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly formatDate = formatDate;

  move(taskId: number, days: number): void {
    this.apply(this.store.shiftTask(taskId, days));
  }

  /** Moves a task to the dates the API suggested for it, exactly as they came. */
  applyFix(fix: MoveTo): void {
    this.apply(this.store.updateTask(fix.taskId, { startDate: fix.startDate, dueDate: fix.dueDate }));
  }

  private apply(request: Observable<TaskChange>): void {
    this.error.set(null);
    this.message.set(null);
    request.subscribe({
      next: (change) => {
        if (change.rescheduled.length) {
          this.message.set(
            `${change.task.title} moved; ${change.rescheduled.length} dependent task${change.rescheduled.length === 1 ? ' was' : 's were'} pushed back.`,
          );
        }
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
