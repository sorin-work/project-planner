import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { messageOf } from '../../core/errors';
import { Task, TaskStatus } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate } from '../../shared/dates';
import { TASK_STATUSES, TASK_STATUS_LABELS, formatHours } from '../../shared/format';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';

/** Index a card should take when dropped at {@code y} within a column of cards. */
export function dropIndex(cardMiddles: number[], y: number): number {
  const index = cardMiddles.findIndex((middle) => y < middle);
  return index === -1 ? cardMiddles.length : index;
}

@Component({
  selector: 'app-board-tab',
    template: `
    @if (error()) {
      <p class="alert error">{{ error() }}</p>
    }
    <div class="board">
      @for (column of columns(); track column.status) {
        <section class="board-column" [class.drop-target]="dragOver() === column.status"
          (dragover)="onDragOver($event, column.status)" (dragleave)="dragOver.set(null)" (drop)="onDrop($event, column.status)">
          <h2>{{ labels[column.status] }} <span class="muted">{{ column.tasks.length }}</span></h2>
          @for (task of column.tasks; track task.id) {
            <article class="board-card" [attr.draggable]="org.isTeam()" (dragstart)="onDragStart($event, task)"
              (dragend)="dragging.set(null)" [class.dragging]="dragging() === task.id"
              [class.critical]="store.criticalIds().has(task.id)" tabindex="0" (keydown.enter)="openTask(task.id)" (click)="openTask(task.id)">
              <p class="card-title">
                @if (task.milestone) { <span class="diamond">◆</span> }
                {{ task.title }}
              </p>
              <div class="card-meta">
                <span [class]="'priority priority-' + task.priority.toLowerCase()">{{ task.priority.toLowerCase() }}</span>
                @if (task.dueDate) {
                  <span class="small" [class.danger]="task.status !== 'DONE' && task.dueDate < todayIso">{{ formatDate(task.dueDate) }}</span>
                }
                @if (task.estimateHours) {
                  <span class="small muted">{{ formatHours(task.estimateHours) }}</span>
                }
                @if (task.subtaskCount) {
                  <span class="small muted">☑ {{ task.subtaskCount }}</span>
                }
                <span class="spacer"></span>
              </div>
            </article>
          }
        </section>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoardTab {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  protected readonly openTask = injectOpenTask();

  protected readonly dragging = signal<number | null>(null);
  protected readonly dragOver = signal<TaskStatus | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly labels = TASK_STATUS_LABELS;
  protected readonly formatDate = formatDate;
  protected readonly formatHours = formatHours;
  protected readonly todayIso = new Date().toISOString().slice(0, 10);

  protected readonly columns = computed(() =>
    TASK_STATUSES.map((status) => ({
      status,
      tasks: this.store
        .topLevel()
        .filter((t) => t.status === status)
        .sort((a, b) => a.position - b.position || a.id - b.id),
    })),
  );

  onDragStart(event: DragEvent, task: Task): void {
    this.dragging.set(task.id);
    event.dataTransfer?.setData('text/plain', String(task.id));
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onDragOver(event: DragEvent, status: TaskStatus): void {
    if (this.dragging() === null) {
      return;
    }
    event.preventDefault();
    this.dragOver.set(status);
  }

  onDrop(event: DragEvent, status: TaskStatus): void {
    event.preventDefault();
    this.dragOver.set(null);
    const id = this.dragging();
    this.dragging.set(null);
    if (id === null) {
      return;
    }
    const column = event.currentTarget as HTMLElement;
    const cards = Array.from(column.querySelectorAll<HTMLElement>('.board-card')).filter(
      (el) => !el.classList.contains('dragging'),
    );
    const position = dropIndex(cards.map((el) => {
      const rect = el.getBoundingClientRect();
      return rect.top + rect.height / 2;
    }), event.clientY);
    const task = this.store.byId().get(id);
    if (!task) {
      return;
    }
    // Optimistic: show it in the new column straight away.
    this.store.tasks.update((all) => all.map((t) => (t.id === id ? { ...t, status, position: position - 0.5 } : t)));
    this.store.updateTask(id, { status, position }).subscribe({
      next: () => this.store.reloadTasks(),
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.store.reloadTasks();
      },
    });
  }
}
