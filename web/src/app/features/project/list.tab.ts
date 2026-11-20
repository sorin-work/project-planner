import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { TaskStatus } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatRange, today } from '../../shared/dates';
import { PRIORITIES, PRIORITY_LABELS, TASK_STATUSES, TASK_STATUS_LABELS, formatHours } from '../../shared/format';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';
import { TaskFilter, assigneesOf, filterFromParams, filterRows, filterToParams, isFiltering } from './task-filter';
import { groupTasks } from './task-groups';

@Component({
  selector: 'app-list-tab',
  imports: [FormsModule],
  template: `
    <div class="toolbar task-filters">
      <input type="search" class="compact" placeholder="Filter tasks…" aria-label="Filter tasks by text" [value]="filter().text"
        (input)="setFilter({ text: $any($event.target).value })" />
      <select class="compact" aria-label="Filter by assignee" [ngModel]="filter().assignee ?? ''"
        (ngModelChange)="setFilter({ assignee: $event === '' ? null : $event === 'none' ? 'none' : +$event })">
        <option value="">Anyone</option>
        <option value="none">Nobody</option>
        @for (person of assignees(); track person.id) {
          <option [value]="person.id">{{ person.name }}</option>
        }
      </select>
      <select class="compact" aria-label="Filter by status" [ngModel]="filter().status ?? ''"
        (ngModelChange)="setFilter({ status: $event || null })">
        <option value="">Any status</option>
        @for (s of statuses; track s) {
          <option [value]="s">{{ statusLabels[s] }}</option>
        }
      </select>
      <select class="compact" aria-label="Filter by priority" [ngModel]="filter().priority ?? ''"
        (ngModelChange)="setFilter({ priority: $event || null })">
        <option value="">Any priority</option>
        @for (p of priorities; track p) {
          <option [value]="p">{{ priorityLabels[p] }}</option>
        }
      </select>
      <label><input type="checkbox" [ngModel]="filter().hideDone" (ngModelChange)="setFilter({ hideDone: $event })" /> Hide done</label>
      <label><input type="checkbox" [ngModel]="filter().mineOnly" (ngModelChange)="setFilter({ mineOnly: $event })" /> Only mine</label>
      @if (filtering()) {
        <button type="button" class="link" (click)="clearFilter()">Clear</button>
      }
      <span class="spacer"></span>
      <span class="muted small" role="status">
        @if (filtering()) {
          {{ shown() }} of {{ store.tasks().length }} tasks
        } @else {
          {{ store.tasks().length }} tasks
        }
      </span>
    </div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    @for (group of groups(); track group.phase?.id ?? 0) {
      <section class="card task-group">
        <h2>{{ group.phase?.name ?? 'No phase' }}</h2>
        <table class="table tasks-table">
          <thead>
            <tr>
              <th>Task</th><th>Status</th><th>Assignee</th><th>Dates</th><th class="num">Estimate</th><th class="num">Logged</th><th>Priority</th>
            </tr>
          </thead>
          <tbody>
            @for (row of group.rows; track row.task.id) {
              <tr [class.subtask]="row.depth > 0" [class.critical]="store.criticalIds().has(row.task.id)" [class.context-row]="row.context"
                [attr.title]="row.context ? 'Shown because a subtask matches the filter' : null">
                <td>
                  <button type="button" class="link task-title" [attr.data-task]="row.task.id" (click)="openTask(row.task.id)">
                    @if (row.task.milestone) { <span class="diamond">◆</span> }
                    {{ row.task.title }}
                  </button>
                  @if (row.task.predecessors.length) {
                    <span class="muted small" title="Waits for other tasks">⛓ {{ row.task.predecessors.length }}</span>
                  }
                </td>
                <td>
                  @if (org.isTeam()) {
                    <select class="compact" [ngModel]="row.task.status" (ngModelChange)="setStatus(row.task.id, $event)"
                      [attr.aria-label]="'Status of ' + row.task.title">
                      @for (s of statuses; track s) {
                        <option [value]="s">{{ statusLabels[s] }}</option>
                      }
                    </select>
                  } @else {
                    {{ statusLabels[row.task.status] }}
                  }
                </td>
                <td>{{ row.task.assigneeName || '–' }}</td>
                <td class="nowrap" [class.danger]="isOverdue(row.task)">{{ formatRange(row.task.startDate, row.task.dueDate) }}</td>
                <td class="num">{{ row.task.milestone ? '' : formatHours(row.task.estimateHours) }}</td>
                <td class="num">{{ row.task.loggedHours ? formatHours(row.task.loggedHours) : '' }}</td>
                <td><span [class]="'priority priority-' + row.task.priority.toLowerCase()">{{ priorityLabels[row.task.priority] }}</span></td>
              </tr>
            }
          </tbody>
        </table>
        @if (org.isTeam()) {
          <form class="quick-add" (ngSubmit)="add(group.phase?.id ?? null)">
            <input [name]="'new-' + (group.phase?.id ?? 0)" placeholder="Add a task…" [(ngModel)]="drafts[group.phase?.id ?? 0]"
              [attr.aria-label]="'New task in ' + (group.phase?.name ?? 'no phase')" />
            <button type="submit" class="small">Add</button>
          </form>
        }
      </section>
    } @empty {
      <p class="muted">No tasks yet.</p>
    }
    @if (filtering() && shown() === 0 && store.tasks().length > 0) {
      <p class="muted">No task matches the filter.</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListTab {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  private readonly auth = inject(AuthService);
  protected readonly openTask = injectOpenTask();

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected drafts: Record<number, string> = {};
  protected readonly error = signal<string | null>(null);

  /** The filter lives in the address, so it is still there after a reload and when coming back. */
  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  protected readonly filter = computed(() => filterFromParams(this.params()));
  protected readonly filtering = computed(() => isFiltering(this.filter()));
  protected readonly assignees = computed(() => assigneesOf(this.store.tasks()));

  protected readonly groups = computed(() => {
    const filter = this.filter();
    const me = this.auth.user()?.id;
    return groupTasks(this.store.tasks(), this.store.phases())
      .map((group) => ({ phase: group.phase, rows: filterRows(group.rows, filter, me) }))
      // A phase without a task that passes says nothing while filtering. Without a filter it is where tasks get added.
      .filter((group) => group.rows.length > 0 || !isFiltering(filter));
  });
  /** How many tasks pass the filter, not counting the ones that are only there for a subtask. */
  protected readonly shown = computed(() => this.groups().reduce((n, group) => n + group.rows.filter((row) => !row.context).length, 0));

  protected readonly statuses = TASK_STATUSES;
  protected readonly priorities = PRIORITIES;
  protected readonly statusLabels = TASK_STATUS_LABELS;
  protected readonly priorityLabels = PRIORITY_LABELS;
  protected readonly formatRange = formatRange;
  protected readonly formatHours = formatHours;
  private readonly todayIso = today();

  /** Changes part of the filter. The address is replaced, so that Back leaves the list instead of undoing keystrokes. */
  setFilter(change: Partial<TaskFilter>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: filterToParams({ ...this.filter(), ...change }),
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  clearFilter(): void {
    this.setFilter({ text: '', assignee: null, status: null, priority: null, hideDone: false, mineOnly: false });
  }

  isOverdue(task: { status: TaskStatus; dueDate?: string }): boolean {
    return task.status !== 'DONE' && !!task.dueDate && task.dueDate < this.todayIso;
  }

  setStatus(id: number, status: TaskStatus): void {
    this.store.updateTask(id, { status }).subscribe({ error: (err: unknown) => this.error.set(messageOf(err)) });
  }

  add(phaseId: number | null): void {
    const key = phaseId ?? 0;
    const title = (this.drafts[key] ?? '').trim();
    if (!title) {
      return;
    }
    this.store.createTask({ title, phaseId }).subscribe({
      next: () => (this.drafts[key] = ''),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
