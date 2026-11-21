import { ChangeDetectionStrategy, Component, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { Member, SearchHit, TaskStatus } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { SearchApi } from '../../core/search.api';
import { TaskApi } from '../../core/task.api';
import { formatDate, today } from '../../shared/dates';
import { PRIORITY_LABELS } from '../../shared/format';
import { StatusPill } from '../../shared/status-pill';
import { groupMyTasks } from './my-tasks';

const OPEN: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'IN_REVIEW'];
const LIMIT = 200;

@Component({
  selector: 'app-my-tasks-page',
  imports: [RouterLink, StatusPill],
  template: `
    <div class="page-header">
      <h1>{{ mine() ? 'My tasks' : 'Tasks of ' + personName() }}</h1>
      <div class="toolbar">
        @if (org.isTeam() && members().length > 1) {
          <label for="assignee" class="sr-only">Whose tasks</label>
          <select id="assignee" [value]="assigneeId() ?? ''" (change)="pick($event)">
            @for (m of members(); track m.userId) {
              <option [value]="m.userId">{{ m.userId === auth.user()?.id ? 'Me' : m.name }}</option>
            }
          </select>
        }
        <label class="checkbox">
          <input type="checkbox" [checked]="showDone()" (change)="showDone.set(!showDone())" />
          Show done
        </label>
      </div>
    </div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    @if (loaded()) {
      @for (group of groups(); track group.key) {
        <section class="card">
          <h2 [class.danger]="group.key === 'overdue'">{{ group.label }} <span class="muted small">{{ group.tasks.length }}</span></h2>
          <ul class="task-lines">
            @for (t of group.tasks; track t.id) {
              <li>
                <input type="checkbox" [checked]="t.status === 'DONE'" [disabled]="saving().has(t.id)"
                  [attr.aria-label]="(t.status === 'DONE' ? 'Not done: ' : 'Done: ') + t.title" (change)="tick(t)" />
                <span class="muted small">{{ t.projectCode }}</span>
                <a class="grow" [routerLink]="['/projects', t.projectId, 'list']" [queryParams]="{ task: t.id }">{{ t.title }}</a>
                @if (t.dueDate) {
                  <span class="small" [class.danger]="group.key === 'overdue'">due {{ formatDate(t.dueDate, day()) }}</span>
                }
                @if (t.status) {
                  <app-status-pill [status]="t.status" />
                }
                @if (t.priority && t.priority !== 'MEDIUM') {
                  <span [class]="'priority priority-' + t.priority.toLowerCase()">{{ priorityLabels[t.priority] }}</span>
                }
              </li>
            }
          </ul>
        </section>
      } @empty {
        <section class="card">
          <p class="muted">{{ showDone() ? 'No tasks.' : 'Nothing open. Enjoy it while it lasts.' }}</p>
        </section>
      }
      @if (more()) {
        <p class="muted small">Only the first {{ limit }} are shown, the earliest due first.</p>
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyTasksPage {
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContext);
  private readonly search = inject(SearchApi);
  private readonly tasks = inject(TaskApi);
  private readonly orgApi = inject(OrgApi);

  protected readonly limit = LIMIT;
  protected readonly priorityLabels = PRIORITY_LABELS;
  protected readonly formatDate = formatDate;

  protected readonly members = signal<Member[]>([]);
  /** Whose tasks: the person signed in unless somebody else was picked. Forgotten when the organisation changes. */
  protected readonly picked = linkedSignal<number | null, number | null>({ source: () => this.org.orgId(), computation: () => null });
  protected readonly assigneeId = computed(() => this.picked() ?? this.auth.user()?.id ?? null);
  protected readonly mine = computed(() => this.assigneeId() === this.auth.user()?.id);
  protected readonly personName = computed(() => this.members().find((m) => m.userId === this.assigneeId())?.name ?? '');
  protected readonly showDone = signal(false);

  protected readonly hits = signal<SearchHit[]>([]);
  protected readonly more = signal(false);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The organisation's date from the API, the browser's until it answered. */
  protected readonly day = signal(today());
  protected readonly saving = signal<ReadonlySet<number>>(new Set());
  /** What a task was before it was ticked done here, to go back to when it is unticked. */
  private readonly before = new Map<number, TaskStatus>();

  protected readonly groups = computed(() => groupMyTasks(this.hits(), this.day()));

  constructor() {
    effect((onCleanup) => {
      const orgId = this.org.orgId();
      // Another organisation: nothing of the last one is kept, its people and its tasks included.
      this.members.set([]);
      this.before.clear();
      this.hits.set([]);
      this.loaded.set(false);
      if (orgId !== null && this.org.isTeam()) {
        const request = this.orgApi.members(orgId)
          .subscribe({ next: (members) => this.members.set(members.filter((m) => m.role !== 'CLIENT')) });
        onCleanup(() => request.unsubscribe());
      }
    });
    effect((onCleanup) => {
      const orgId = this.org.orgId();
      const assigneeId = this.assigneeId();
      const showDone = this.showDone();
      if (orgId === null || assigneeId === null) {
        return;
      }
      this.error.set(null);
      // A new question cancels the one before it, so that a slow answer about somebody else,
      // or with done tasks or without, never lands after the one that was asked for last.
      const request = this.search.tasks(orgId, { assigneeId, statuses: showDone ? undefined : OPEN, limit: LIMIT }).subscribe({
        next: (response) => {
          this.hits.set(response.tasks);
          this.more.set(response.more.includes('TASK'));
          if (response.today) {
            this.day.set(response.today);
          }
          this.loaded.set(true);
        },
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
      onCleanup(() => request.unsubscribe());
    });
  }

  pick(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    this.picked.set(value === this.auth.user()?.id ? null : value);
  }

  /** Ticks a task done, or back to what it was, without leaving the page. */
  tick(task: SearchHit): void {
    const status: TaskStatus = task.status === 'DONE' ? (this.before.get(task.id) ?? 'TODO') : 'DONE';
    if (status === 'DONE' && task.status) {
      this.before.set(task.id, task.status);
    }
    this.saving.update((ids) => new Set([...ids, task.id]));
    this.error.set(null);
    const done = () => this.saving.update((ids) => new Set([...ids].filter((id) => id !== task.id)));
    this.tasks.update(task.id, { status }).subscribe({
      next: () => {
        this.hits.update((all) => all.map((t) => (t.id === task.id ? { ...t, status } : t)));
        done();
      },
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        // The box shows what the server has, not the click that failed.
        this.hits.update((all) => all.map((t) => (t.id === task.id ? { ...t } : t)));
        done();
      },
    });
  }
}
