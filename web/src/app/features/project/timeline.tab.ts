import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { OrgContext } from '../../core/org-context.service';
import { Gantt } from '../gantt/gantt';
import { Zoom } from '../gantt/gantt-layout';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';

@Component({
  selector: 'app-timeline-tab',
  imports: [FormsModule, Gantt],
  template: `
    <div class="toolbar">
      <span class="spacer"></span>
    </div>
    @if (org.isTeam()) {
      <p class="muted small">Drag a bar to move it; tasks that depend on it move too.</p>
    }
    @if (message()) {
      <p class="alert info" role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="card gantt-card">
      <app-gantt [tasks]="store.tasks()" [phases]="store.phases()" [critical]="store.criticalIds()"
        [showCritical]="showCritical" [zoom]="zoom()" [editable]="org.isTeam()"
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
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  move(taskId: number, days: number): void {
    this.error.set(null);
    this.message.set(null);
    this.store.shiftTask(taskId, days).subscribe({
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
