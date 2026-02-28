import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { messageOf } from '../../core/errors';
import { OrgContext } from '../../core/org-context.service';
import { formatRange } from '../../shared/dates';
import { PROJECT_STATUS_LABELS } from '../../shared/format';
import { ProjectStore } from './project.store';
import { TaskPanel } from './task-panel';

@Component({
  selector: 'app-project-page',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TaskPanel],
  providers: [ProjectStore],
  template: `
    @if (error()) {
      <p class="alert error">{{ error() }}</p>
      <a routerLink="/projects">Back to projects</a>
    } @else if (store.project(); as p) {
      <div class="page-header project-header">
        <div>
          <p class="muted small"><a routerLink="/projects">Projects</a> / {{ p.clientName || 'Internal' }}</p>
          <h1><span class="swatch big" [style.background]="p.color"></span>{{ p.name }} <span class="muted code">{{ p.code }}</span></h1>
          <p class="muted">{{ statusLabels[p.status] }} · {{ formatRange(p.startDate, p.endDate) }}</p>
        </div>
      </div>
      <nav class="tabs" aria-label="Project views">
        <a routerLink="overview" routerLinkActive="active">Overview</a>
        <a routerLink="list" routerLinkActive="active">List</a>
        <a routerLink="board" routerLinkActive="active">Board</a>
        <a routerLink="timeline" routerLinkActive="active">Timeline</a>
        <a routerLink="activity" routerLinkActive="active">Activity</a>
        @if (org.canManage()) {
          <a routerLink="settings" routerLinkActive="active">Settings</a>
        }
      </nav>
      <div class="project-body" [class.with-panel]="openTaskId() !== null">
        <div class="tab-content"><router-outlet /></div>
        @if (openTaskId(); as taskId) {
          <app-task-panel [taskId]="taskId" (closed)="closeTask()" />
        }
      </div>
    } @else {
      <p class="muted">Loading…</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectPage {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly projectId = input.required<string>();

  protected readonly error = signal<string | null>(null);
  private readonly query = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('task'))), { initialValue: null });
  protected readonly openTaskId = computed(() => {
    const id = this.query();
    return id ? Number(id) : null;
  });

  protected readonly statusLabels = PROJECT_STATUS_LABELS;
  protected readonly formatRange = formatRange;

  constructor() {
    effect(() => {
      const id = Number(this.projectId());
      this.error.set(null);
      this.store.load(id).subscribe({ error: (err: unknown) => this.error.set(messageOf(err)) });
    });
  }

  closeTask(): void {
    void this.router.navigate([], { relativeTo: this.route.firstChild ?? this.route, queryParams: { task: null }, queryParamsHandling: 'merge' });
  }
}
