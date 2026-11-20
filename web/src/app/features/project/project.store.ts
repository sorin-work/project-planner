import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, map, tap } from 'rxjs';
import { messageOf } from '../../core/errors';
import { Baseline, BaselineComparison, CriticalPath, Member, Phase, Project, ScheduleIssue, Task, TaskChange } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { ProjectApi } from '../../core/project.api';
import { NewTask, TaskApi, TaskPatch } from '../../core/task.api';
import { brokenLinks } from './schedule-issues';

/** Everything the project tabs share: the project, its plan and the team. Provided per project page. */
@Injectable()
export class ProjectStore {
  private readonly projects = inject(ProjectApi);
  private readonly tasksApi = inject(TaskApi);
  private readonly orgApi = inject(OrgApi);

  readonly project = signal<Project | null>(null);
  readonly tasks = signal<Task[]>([]);
  readonly phases = signal<Phase[]>([]);
  readonly members = signal<Member[]>([]);
  readonly criticalPath = signal<CriticalPath | null>(null);
  /** Where the plan contradicts itself, as the API sees it. Refetched with the critical path. */
  readonly issues = signal<ScheduleIssue[]>([]);
  /** Why the critical path and the issues could not be refreshed after a change, or null when they are current. */
  readonly scheduleError = signal<string | null>(null);
  readonly loading = signal(true);
  /** The project's baselines, oldest first. */
  readonly baselines = signal<Baseline[]>([]);
  /** The baseline the plan is compared with, or null for none. Starts on the project's reference. */
  readonly comparedId = signal<number | null>(null);
  /** The API's comparison of the plan with that baseline, refetched after every change. */
  readonly comparison = signal<BaselineComparison | null>(null);
  readonly comparisonError = signal<string | null>(null);
  /** Bumped after every change so tabs with their own data (health, activity) can reload. */
  readonly version = signal(0);

  readonly team = computed(() => this.members().filter((m) => m.role !== 'CLIENT'));
  readonly topLevel = computed(() => this.tasks().filter((t) => !t.parentId));
  readonly criticalIds = computed(() => new Set(this.criticalPath()?.criticalTaskIds ?? []));
  readonly byId = computed(() => new Map(this.tasks().map((t) => [t.id, t])));
  readonly brokenLinks = computed(() => brokenLinks(this.issues()));

  private scheduleRequest = 0;
  private comparisonRequest = 0;

  load(projectId: number): Observable<void> {
    this.loading.set(true);
    return forkJoin({
      project: this.projects.get(projectId),
      tasks: this.tasksApi.list(projectId),
      phases: this.projects.phases(projectId),
      critical: this.projects.criticalPath(projectId),
      issues: this.projects.scheduleIssues(projectId),
    }).pipe(
      tap(({ project, tasks, phases, critical, issues }) => {
        this.project.set(project);
        this.tasks.set(tasks);
        this.phases.set(phases);
        // Answers still on their way for the plan as it was no longer count.
        this.scheduleRequest++;
        this.criticalPath.set(critical);
        this.issues.set(issues);
        this.scheduleError.set(null);
        this.loading.set(false);
        this.orgApi.members(project.organisationId).subscribe({ next: (m) => this.members.set(m) });
        this.loadBaselines(true);
      }),
      map(() => undefined),
    );
  }

  subtasks(parentId: number): Task[] {
    return this.tasks().filter((t) => t.parentId === parentId);
  }

  issuesOf(taskId: number): ScheduleIssue[] {
    return this.issues().filter((i) => i.taskId === taskId);
  }

  createTask(body: NewTask): Observable<Task> {
    const project = this.requireProject();
    return this.tasksApi.create(project.id, body).pipe(
      tap((task) => {
        this.tasks.update((all) => [...all, task]);
        this.changed();
      }),
    );
  }

  updateTask(id: number, patch: TaskPatch): Observable<TaskChange> {
    return this.tasksApi.update(id, patch).pipe(tap((change) => this.apply(change)));
  }

  shiftTask(id: number, days: number): Observable<TaskChange> {
    return this.tasksApi.shift(id, days).pipe(tap((change) => this.apply(change)));
  }

  deleteTask(id: number): Observable<void> {
    return this.tasksApi.delete(id).pipe(
      tap(() => {
        // Subtasks and links go with it on the server.
        this.tasks.update((all) =>
          all
            .filter((t) => t.id !== id && t.parentId !== id)
            .map((t) => ({ ...t, predecessors: t.predecessors.filter((d) => d.predecessorId !== id) })),
        );
        this.changed();
      }),
    );
  }

  addDependency(successorId: number, predecessorId: number, lagDays: number): Observable<TaskChange> {
    return this.tasksApi.addDependency(successorId, predecessorId, lagDays).pipe(tap((change) => this.apply(change)));
  }

  removeDependency(successorId: number, dependencyId: number): Observable<void> {
    return this.tasksApi.removeDependency(dependencyId).pipe(
      tap(() => {
        this.tasks.update((all) =>
          all.map((t) =>
            t.id === successorId ? { ...t, predecessors: t.predecessors.filter((d) => d.id !== dependencyId) } : t,
          ),
        );
        this.changed();
      }),
    );
  }

  refreshPhases(): void {
    const project = this.requireProject();
    this.projects.phases(project.id).subscribe({
      next: (p) => {
        this.phases.set(p);
        this.refreshComparison();
      },
    });
  }

  /** Merges a server change (the task plus anything rescheduled because of it) into local state. */
  apply(change: TaskChange): void {
    const updates = new Map<number, Task>([[change.task.id, change.task]]);
    for (const t of change.rescheduled) {
      updates.set(t.id, t);
    }
    this.tasks.update((all) => all.map((t) => updates.get(t.id) ?? t));
    // Board reorders renumber siblings on the server; fetch positions when the status/position changed.
    this.changed();
  }

  private changed(): void {
    this.version.update((v) => v + 1);
    this.refreshSchedule();
    this.refreshComparison();
  }

  /**
   * Refetches the baselines. With {@code pickReference} the comparison switches to the
   * project's reference baseline. Otherwise it stays on the one picked, unless that one
   * is gone.
   */
  loadBaselines(pickReference = false): void {
    const project = this.project();
    if (!project) {
      return;
    }
    this.projects.baselines(project.id).subscribe({
      next: (baselines) => {
        this.baselines.set(baselines);
        const picked = this.comparedId();
        const keep = !pickReference && (picked === null || baselines.some((b) => b.id === picked));
        this.compareWith(keep ? picked : (baselines.find((b) => b.reference)?.id ?? null));
      },
      error: (err: unknown) => this.comparisonError.set(messageOf(err)),
    });
  }

  /** Compares the plan with another baseline, or with none. */
  compareWith(baselineId: number | null): void {
    this.comparedId.set(baselineId);
    this.refreshComparison();
  }

  /** Asks the API for the comparison again. An answer to an older request is dropped, as with the schedule. */
  refreshComparison(): void {
    const id = this.comparedId();
    const request = ++this.comparisonRequest;
    if (id === null) {
      this.comparison.set(null);
      this.comparisonError.set(null);
      return;
    }
    this.projects.baselineComparison(id).subscribe({
      next: (comparison) => {
        if (request === this.comparisonRequest) {
          this.comparison.set(comparison);
          this.comparisonError.set(null);
        }
      },
      error: (err: unknown) => {
        if (request === this.comparisonRequest) {
          // An old comparison would put the thin bars where the plan no longer says.
          this.comparison.set(null);
          this.comparisonError.set(messageOf(err));
        }
      },
    });
  }

  /**
   * Refetches what the API works out from the plan: the critical path and the
   * schedule issues. Changes can follow each other quickly (arrow keys on a bar),
   * so an answer is dropped when a newer request has gone out since.
   *
   * When the refresh fails, what was on screen is from before the change. It is
   * dropped and the error kept for the tabs to show: an old "Move to" could pull a
   * task back in front of a predecessor that has just been moved later.
   */
  refreshSchedule(): void {
    const project = this.project();
    if (!project) {
      return;
    }
    const request = ++this.scheduleRequest;
    forkJoin({
      critical: this.projects.criticalPath(project.id),
      issues: this.projects.scheduleIssues(project.id),
    }).subscribe({
      next: ({ critical, issues }) => {
        if (request === this.scheduleRequest) {
          this.criticalPath.set(critical);
          this.issues.set(issues);
          this.scheduleError.set(null);
        }
      },
      error: (err: unknown) => {
        if (request === this.scheduleRequest) {
          this.criticalPath.set(null);
          this.issues.set([]);
          this.scheduleError.set(messageOf(err));
        }
      },
    });
  }

  reloadTasks(): void {
    const project = this.requireProject();
    this.tasksApi.list(project.id).subscribe({ next: (tasks) => this.tasks.set(tasks) });
  }

  private requireProject(): Project {
    const project = this.project();
    if (!project) {
      throw new Error('Project not loaded');
    }
    return project;
  }
}
