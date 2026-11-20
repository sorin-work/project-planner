import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Announcer } from '../../core/announcer';
import { messageOf } from '../../core/errors';
import { Task, TaskStatus } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate } from '../../shared/dates';
import { TASK_STATUSES, TASK_STATUS_LABELS, formatHours } from '../../shared/format';
import { Avatar } from '../../shared/avatar';
import { injectOpenTask } from './open-task';
import { ProjectStore } from './project.store';

/** Index a card should take when dropped at {@code y} within a column of cards. */
export function dropIndex(cardMiddles: number[], y: number): number {
  const index = cardMiddles.findIndex((middle) => y < middle);
  return index === -1 ? cardMiddles.length : index;
}

/**
 * Where Alt with an arrow key takes a card: left and right to the column before or after,
 * at its end; up and down one place within its column. Null when there is nowhere to go.
 */
export function keyboardMove(
  columns: { status: TaskStatus; tasks: { id: number }[] }[],
  taskId: number,
  key: string,
): { status: TaskStatus; position: number } | null {
  const from = columns.findIndex((c) => c.tasks.some((t) => t.id === taskId));
  if (from === -1) {
    return null;
  }
  const index = columns[from].tasks.findIndex((t) => t.id === taskId);
  switch (key) {
    case 'ArrowLeft':
    case 'ArrowRight': {
      const to = columns[from + (key === 'ArrowLeft' ? -1 : 1)];
      return to ? { status: to.status, position: to.tasks.length } : null;
    }
    case 'ArrowUp':
      return index > 0 ? { status: columns[from].status, position: index - 1 } : null;
    case 'ArrowDown':
      return index < columns[from].tasks.length - 1 ? { status: columns[from].status, position: index + 1 } : null;
    default:
      return null;
  }
}

/** "left", "right", "up" or "down", for saying why a card did not move. */
function direction(key: string): string {
  return key.replace('Arrow', '').toLowerCase();
}

@Component({
  selector: 'app-board-tab',
  imports: [Avatar],
  template: `
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    @if (org.isTeam()) {
      <p id="board-keys" class="muted small">Drag a card to another column, or focus it and press Alt with the arrow keys:
        left and right change its status, up and down its place.</p>
    }
    <div class="board">
      @for (column of columns(); track column.status) {
        <section class="board-column" [class.drop-target]="dragOver() === column.status"
          (dragover)="onDragOver($event, column.status)" (dragleave)="dragOver.set(null)" (drop)="onDrop($event, column.status)">
          <h2>{{ labels[column.status] }} <span class="muted">{{ column.tasks.length }}</span></h2>
          @for (task of column.tasks; track task.id) {
            <article class="board-card" [attr.draggable]="org.isTeam()" (dragstart)="onDragStart($event, task)"
              (dragend)="dragging.set(null)" [class.dragging]="dragging() === task.id"
              [class.critical]="store.criticalIds().has(task.id)" tabindex="0" [attr.data-card]="task.id"
              [attr.aria-describedby]="org.isTeam() ? 'board-keys' : null"
              (keydown.enter)="openTask(task.id)" (keydown)="onCardKey($event, task)" (click)="openTask(task.id)">
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
                @if (task.assigneeName) {
                  <app-avatar [name]="task.assigneeName" [small]="true" />
                }
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
  private readonly announcer = inject(Announcer);

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

  /** Alt and an arrow key: the same move as a drag, through the same update, and said out loud. */
  onCardKey(event: KeyboardEvent, task: Task): void {
    if (!event.altKey || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
      return;
    }
    // Alt with an arrow is the browser's Back and Forward. On a card it is always the card's,
    // whether it moves or not, so that a card at the edge does not leave the page.
    event.preventDefault();
    if (!this.org.isTeam()) {
      this.announcer.announce('Only the team can move cards.');
      return;
    }
    const move = keyboardMove(this.columns(), task.id, event.key);
    if (!move) {
      this.announcer.announce(`${task.title} cannot go further ${direction(event.key)}.`);
      return;
    }
    this.error.set(null);
    this.store.updateTask(task.id, move).subscribe({
      next: () => {
        this.store.reloadTasks();
        this.announcer.announce(`${task.title} moved to ${this.labels[move.status]}, place ${move.position + 1}.`);
        // The card is drawn again in its new place: the focus stays on it.
        setTimeout(() => document.querySelector<HTMLElement>(`[data-card="${task.id}"]`)?.focus());
      },
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.announcer.alert(`${task.title} was not moved: ${messageOf(err)}`);
      },
    });
  }

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
