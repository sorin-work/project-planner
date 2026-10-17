import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { ActivityEntry } from '../../core/models';
import { ProjectApi } from '../../core/project.api';
import { timeAgo } from '../../shared/dates';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';

@Component({
  selector: 'app-activity-tab',
  template: `
    <section class="card">
      <ul class="activity">
        @for (a of entries(); track a.id) {
          <li>
            <span>
              <strong>{{ a.actorName || 'Someone' }}</strong> · {{ a.summary }}
              @if (a.taskId) {
                <button type="button" class="link small" (click)="openTask(a.taskId)">open</button>
              }
            </span>
            <span class="muted small" [title]="a.createdAt">{{ timeAgo(a.createdAt) }}</span>
          </li>
        } @empty {
          <li class="muted">Nothing has happened yet.</li>
        }
      </ul>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActivityTab {
  private readonly store = inject(ProjectStore);
  private readonly api = inject(ProjectApi);
  protected readonly openTask = injectOpenTask();
  protected readonly entries = signal<ActivityEntry[]>([]);
  protected readonly timeAgo = timeAgo;

  constructor() {
    effect(() => {
      const project = this.store.project();
      this.store.version();
      if (project) {
        this.api.activity(project.id, 100).subscribe({ next: (e) => this.entries.set(e) });
      }
    });
  }
}
