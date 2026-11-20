import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { Comment, Priority, Task, TaskStatus } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { TaskApi, TaskPatch } from '../../core/task.api';
import { formatDate, formatRange, timeAgo } from '../../shared/dates';
import { PRIORITIES, PRIORITY_LABELS, TASK_STATUSES, TASK_STATUS_LABELS, formatHours } from '../../shared/format';
import { segments } from '../../shared/mentions';
import { TimerService } from '../time/timer.service';
import { CommentBox } from './comment-box';
import { ProjectStore } from './project.store';
import { issueText, linkIssue, linkNote, moveCost, moveTo } from './schedule-issues';

@Component({
  selector: 'app-task-panel',
  imports: [FormsModule, CommentBox],
  templateUrl: './task-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaskPanel {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  protected readonly timer = inject(TimerService);
  private readonly api = inject(TaskApi);
  private readonly auth = inject(AuthService);

  readonly taskId = input.required<number>();
  readonly closed = output();

  private readonly commentBox = viewChild(CommentBox);
  protected readonly comments = signal<Comment[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly posting = signal(false);
  protected newSubtask = '';
  protected newPredecessor: number | null = null;
  protected newLag = 0;

  protected readonly task = computed<Task | undefined>(() => this.store.byId().get(this.taskId()));
  protected readonly subtasks = computed(() => this.store.subtasks(this.taskId()));
  protected readonly parent = computed(() => {
    const parentId = this.task()?.parentId;
    return parentId ? this.store.byId().get(parentId) : undefined;
  });
  protected readonly predecessorOptions = computed(() => {
    const task = this.task();
    if (!task) {
      return [];
    }
    const existing = new Set(task.predecessors.map((d) => d.predecessorId));
    return this.store.tasks().filter((t) => t.id !== task.id && !existing.has(t.id));
  });
  protected readonly dependants = computed(() =>
    this.store.tasks().filter((t) => t.predecessors.some((d) => d.predecessorId === this.taskId())),
  );
  protected readonly issues = computed(() => this.store.issuesOf(this.taskId()));
  /** The dates the API suggests when this task starts before something it waits for allows. */
  protected readonly fix = computed(() => moveTo(this.issues()));
  /** Issues about the task's own dates. The ones about a link are shown on that link. */
  protected readonly dateNotes = computed(() =>
    this.issues()
      .filter((i) => i.type === 'AFTER_PROJECT_END' || i.type === 'OUTSIDE_PARENT')
      .map((i) => issueText(i, (id) => this.taskTitle(id))),
  );
  protected readonly handles = computed(() => new Set(this.store.members().map((m) => m.handle)));
  protected readonly canEdit = computed(() => this.org.isTeam());
  protected readonly isCritical = computed(() => this.store.criticalIds().has(this.taskId()));
  protected readonly timing = computed(() => this.store.criticalPath()?.tasks.find((t) => t.taskId === this.taskId()));
  protected readonly timerHere = computed(() => this.timer.running()?.taskId === this.taskId());

  protected readonly statuses = TASK_STATUSES;
  protected readonly statusLabels = TASK_STATUS_LABELS;
  protected readonly priorities = PRIORITIES;
  protected readonly priorityLabels = PRIORITY_LABELS;
  protected readonly formatDate = formatDate;
  protected readonly formatRange = formatRange;
  protected readonly formatHours = formatHours;
  protected readonly linkIssue = linkIssue;
  protected readonly linkNote = linkNote;
  protected readonly moveCost = moveCost;
  protected readonly timeAgo = timeAgo;
  protected readonly segments = segments;

  constructor() {
    effect(() => {
      const id = this.taskId();
      this.error.set(null);
      this.notice.set(null);
      this.api.comments(id).subscribe({ next: (c) => this.comments.set(c), error: () => this.comments.set([]) });
    });
  }

  protected readonly taskTitleOf = (t: Task) => t.title;

  taskTitle(id: number): string {
    return this.store.byId().get(id)?.title ?? `#${id}`;
  }

  save(patch: TaskPatch): void {
    this.error.set(null);
    this.notice.set(null);
    this.store.updateTask(this.taskId(), patch).subscribe({
      next: (change) => {
        if (change.rescheduled.length) {
          this.notice.set(
            `Moved ${change.rescheduled.length} dependent task${change.rescheduled.length === 1 ? '' : 's'} to keep the plan consistent: ` +
              change.rescheduled.map((t) => t.title).join(', '),
          );
        }
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  setTitle(value: string): void {
    const title = value.trim();
    if (title && title !== this.task()?.title) {
      this.save({ title });
    }
  }

  setDescription(value: string): void {
    if (value !== (this.task()?.description ?? '')) {
      this.save({ description: value });
    }
  }

  setStatus(status: TaskStatus): void {
    this.save({ status });
  }

  setPriority(priority: Priority): void {
    this.save({ priority });
  }

  setAssignee(value: string): void {
    this.save(value ? { assigneeId: Number(value) } : { clearAssignee: true });
  }

  setPhase(value: string): void {
    this.save(value ? { phaseId: Number(value) } : { clearPhase: true });
  }

  setEstimate(value: string): void {
    if (value !== '') {
      this.save({ estimateHours: Number(value) });
    }
  }

  setStart(value: string): void {
    if (value) {
      this.save({ startDate: value });
    }
  }

  setDue(value: string): void {
    if (value) {
      this.save({ dueDate: value });
    }
  }

  clearDates(): void {
    this.save({ clearDates: true });
  }

  /** "Move to": sends the two suggested dates back exactly as the API gave them. */
  applyFix(): void {
    const fix = this.fix();
    if (fix) {
      this.save({ startDate: fix.startDate, dueDate: fix.dueDate });
    }
  }

  addSubtask(): void {
    const title = this.newSubtask.trim();
    const task = this.task();
    if (!title || !task) {
      return;
    }
    this.store.createTask({ title, parentId: task.id, phaseId: task.phaseId ?? null }).subscribe({
      next: () => {
        this.newSubtask = '';
        this.store.reloadTasks();
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  toggleSubtask(sub: Task): void {
    this.store.updateTask(sub.id, { status: sub.status === 'DONE' ? 'TODO' : 'DONE' }).subscribe({
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  addDependency(): void {
    if (this.newPredecessor === null) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.store.addDependency(this.taskId(), this.newPredecessor, this.newLag || 0).subscribe({
      next: (change) => {
        this.newPredecessor = null;
        this.newLag = 0;
        if (change.rescheduled.length) {
          this.notice.set(`Rescheduled: ${change.rescheduled.map((t) => t.title).join(', ')}`);
        }
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  removeDependency(dependencyId: number): void {
    this.store.removeDependency(this.taskId(), dependencyId).subscribe({
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  postComment(body: string): void {
    this.posting.set(true);
    this.api.addComment(this.taskId(), body).subscribe({
      next: (comment) => {
        this.comments.update((all) => [...all, comment]);
        this.commentBox()?.clear();
        this.posting.set(false);
      },
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.posting.set(false);
      },
    });
  }

  deleteComment(comment: Comment): void {
    this.api.deleteComment(comment.id).subscribe({
      next: () => this.comments.update((all) => all.filter((c) => c.id !== comment.id)),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  toggleTimer(): void {
    const orgId = this.org.id();
    const task = this.task();
    if (!task) {
      return;
    }
    const request = this.timerHere() ? this.timer.stop(orgId) : this.timer.start(orgId, task.projectId, task.id);
    request.subscribe({
      next: () => this.store.reloadTasks(),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remove(): void {
    const task = this.task();
    if (!task || !confirm(`Delete "${task.title}"${task.subtaskCount ? ' and its subtasks' : ''}?`)) {
      return;
    }
    this.store.deleteTask(task.id).subscribe({
      next: () => this.closed.emit(),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  canDelete(comment: Comment): boolean {
    return comment.authorId === this.auth.user()?.id || this.org.canManage();
  }
}
