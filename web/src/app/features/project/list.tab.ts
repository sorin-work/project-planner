import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { TaskStatus } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatRange } from '../../shared/dates';
import { PRIORITY_LABELS, TASK_STATUSES, TASK_STATUS_LABELS, formatHours } from '../../shared/format';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';
import { groupTasks } from './task-groups';

@Component({
  selector: 'app-list-tab',
  imports: [FormsModule],
  template: `
    <div class="toolbar">
      <label><input type="checkbox" [(ngModel)]="hideDone" /> Hide done</label>
      <label><input type="checkbox" [(ngModel)]="mineOnly" /> Only mine</label>
      <span class="spacer"></span>
      <span class="muted small">{{ store.tasks().length }} tasks</span>
    </div>
    @if (error()) {
      <p class="alert error">{{ error() }}</p>
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
            @for (row of visibleRows(group.rows); track row.task.id) {
              <tr [class.subtask]="row.depth > 0" [class.critical]="store.criticalIds().has(row.task.id)">
                <td>
                  <button type="button" class="link task-title" (click)="openTask(row.task.id)">
                    @if (row.task.milestone) { <span class="diamond">◆</span> }
                    {{ row.task.title }}
                  </button>
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
                <td class="nowrap">{{ formatRange(row.task.startDate, row.task.dueDate) }}</td>
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ListTab {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  private readonly auth = inject(AuthService);
  protected readonly openTask = injectOpenTask();

  protected hideDone = false;
  protected mineOnly = false;
  protected drafts: Record<number, string> = {};
  protected readonly error = signal<string | null>(null);

  protected readonly groups = computed(() => groupTasks(this.store.tasks(), this.store.phases()));
  protected readonly statuses = TASK_STATUSES;
  protected readonly statusLabels = TASK_STATUS_LABELS;
  protected readonly priorityLabels = PRIORITY_LABELS;
  protected readonly formatRange = formatRange;
  protected readonly formatHours = formatHours;

  visibleRows<T extends { task: { status: TaskStatus; assigneeId?: number } }>(rows: T[]): T[] {
    return rows.filter((r) => (!this.hideDone || r.task.status !== 'DONE') && (!this.mineOnly || this.isMine(r.task.assigneeId)));
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

  private isMine(assigneeId: number | undefined): boolean {
    return assigneeId !== undefined && assigneeId === this.auth.user()?.id;
  }
}
