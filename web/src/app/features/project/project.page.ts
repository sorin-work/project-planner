import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { messageOf } from '../../core/errors';
import { projectTitle } from '../../core/page-title';
import { Title } from '@angular/platform-browser';
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
      <p class="alert error" role="alert">{{ error() }}</p>
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
        <a routerLink="overview" routerLinkActive="active" ariaCurrentWhenActive="page">Overview</a>
        <a routerLink="list" routerLinkActive="active" ariaCurrentWhenActive="page">List</a>
        <a routerLink="board" routerLinkActive="active" ariaCurrentWhenActive="page">Board</a>
        <a routerLink="timeline" routerLinkActive="active" ariaCurrentWhenActive="page">Timeline</a>
        <a routerLink="activity" routerLinkActive="active" ariaCurrentWhenActive="page">Activity</a>
        @if (org.canManage()) {
          <a routerLink="settings" routerLinkActive="active" ariaCurrentWhenActive="page">Settings</a>
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
  private readonly title = inject(Title);
  private readonly navigations = toSignal(this.router.events.pipe(filter((e) => e instanceof NavigationEnd)));

  protected readonly error = signal<string | null>(null);
  private readonly query = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('task'))), { initialValue: null });
  protected readonly openTaskId = computed(() => {
    const id = this.query();
    return id ? Number(id) : null;
  });

  protected readonly statusLabels = PROJECT_STATUS_LABELS;
  protected readonly formatRange = formatRange;

  /** What had the focus when the task panel opened, to give it back when the panel closes. */
  private opener: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const open = this.openTaskId() !== null;
      const active = document.activeElement as HTMLElement | null;
      if (open && !this.opener && active && !active.closest('.task-panel')) {
        this.opener = active;
      } else if (!open && this.opener) {
        const opener = this.opener;
        this.opener = null;
        // After the panel is gone. A row or a bar that was drawn again is found by what it stands for.
        setTimeout(() => (opener.isConnected ? opener : this.sameAs(opener))?.focus());
      }
    });
    // The project's name in the title of the tab: "Timeline · Website · project-planner".
    effect(() => {
      const project = this.store.project();
      this.navigations();
      if (project) {
        setTimeout(() => this.title.setTitle(projectTitle(this.title.getTitle(), project.name)));
      }
    });
    effect(() => {
      const id = Number(this.projectId());
      this.error.set(null);
      this.store.load(id).subscribe({ error: (err: unknown) => this.error.set(messageOf(err)) });
    });
  }

  /** The element that now stands for the task {@code opener} stood for, by its data-task or data-card. */
  private sameAs(opener: HTMLElement): HTMLElement | null {
    const id = opener.getAttribute('data-task') ?? opener.getAttribute('data-card');
    return id ? document.querySelector<HTMLElement>(`[data-task="${id}"], [data-card="${id}"]`) : null;
  }

  closeTask(): void {
    void this.router.navigate([], { relativeTo: this.route.firstChild ?? this.route, queryParams: { task: null }, queryParamsHandling: 'merge' });
  }
}
