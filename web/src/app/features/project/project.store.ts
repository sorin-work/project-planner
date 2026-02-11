import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, map, tap } from 'rxjs';
import { CriticalPath, Member, Phase, Project, Task, TaskChange } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { ProjectApi } from '../../core/project.api';
import { NewTask, TaskApi, TaskPatch } from '../../core/task.api';

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
  readonly loading = signal(true);
  /** Bumped after every change so tabs with their own data (health, activity) can reload. */
  readonly version = signal(0);

  readonly team = computed(() => this.members().filter((m) => m.role !== 'CLIENT'));
  readonly topLevel = computed(() => this.tasks().filter((t) => !t.parentId));
  readonly criticalIds = computed(() => new Set(this.criticalPath()?.criticalTaskIds ?? []));
  readonly byId = computed(() => new Map(this.tasks().map((t) => [t.id, t])));

  load(projectId: number): Observable<void> {
    this.loading.set(true);
    return forkJoin({
      project: this.projects.get(projectId),
      tasks: this.tasksApi.list(projectId),
      phases: this.projects.phases(projectId),
      critical: this.projects.criticalPath(projectId),
    }).pipe(
      tap(({ project, tasks, phases, critical }) => {
        this.project.set(project);
        this.tasks.set(tasks);
        this.phases.set(phases);
        this.criticalPath.set(critical);
        this.loading.set(false);
        this.orgApi.members(project.organisationId).subscribe({ next: (m) => this.members.set(m) });
      }),
      map(() => undefined),
    );
  }

  subtasks(parentId: number): Task[] {
    return this.tasks().filter((t) => t.parentId === parentId);
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
    this.projects.phases(project.id).subscribe({ next: (p) => this.phases.set(p) });
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
    const project = this.project();
    if (project) {
      this.projects.criticalPath(project.id).subscribe({ next: (c) => this.criticalPath.set(c) });
    }
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
