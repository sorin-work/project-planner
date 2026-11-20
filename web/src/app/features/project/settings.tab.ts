import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { messageOf } from '../../core/errors';
import { Client, Phase } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi, ProjectInput } from '../../core/project.api';
import { DuplicateForm } from '../projects/duplicate-form';
import { ProjectForm } from '../projects/project-form';
import { ProjectStore } from './project.store';

@Component({
  selector: 'app-settings-tab',
  imports: [FormsModule, ProjectForm, DuplicateForm],
  template: `
    <section class="card">
      <h2>Project details</h2>
      @if (saved()) {
        <p class="alert success">Saved.</p>
      }
      <app-project-form [project]="store.project()" [clients]="clients()" [busy]="busy()" [error]="error()"
        (saved)="save($event)" (cancelled)="reset()" />
    </section>

    <section class="card">
      <h2>Phases</h2>
      <ul class="phase-list">
        @for (phase of store.phases(); track phase.id; let first = $first; let last = $last) {
          <li>
            <input [ngModel]="phase.name" (change)="rename(phase, $any($event.target).value)" [attr.aria-label]="'Name of ' + phase.name" />
            <button type="button" class="small" [disabled]="first" (click)="move(phase, -1)" aria-label="Move up">↑</button>
            <button type="button" class="small" [disabled]="last" (click)="move(phase, 1)" aria-label="Move down">↓</button>
            <button type="button" class="small danger" (click)="removePhase(phase)">Remove</button>
          </li>
        }
      </ul>
      <form class="quick-add" (ngSubmit)="addPhase()">
        <input name="phase" placeholder="New phase name" [(ngModel)]="newPhase" aria-label="New phase name" />
        <button type="submit" class="small">Add phase</button>
      </form>
    </section>

    <section class="card">
      <h2>Duplicate project</h2>
      @if (duplicating() && store.project(); as source) {
        <app-duplicate-form [source]="source" [clients]="clients()" (created)="openCopy($event.id)"
          (cancelled)="duplicating.set(false)" />
      } @else {
        <p class="muted">Copies the phases, tasks and links into a new project that begins on a day you pick.</p>
        <button type="button" (click)="duplicating.set(true)">Duplicate</button>
      }
    </section>

    <section class="card danger-zone">
      <h2>Delete project</h2>
      <p class="muted">Deletes the project with all of its tasks, comments and logged time. This can't be undone.</p>
      <button type="button" class="danger" (click)="remove()">Delete project</button>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsTab {
  protected readonly store = inject(ProjectStore);
  private readonly api = inject(ProjectApi);
  private readonly orgApi = inject(OrgApi);
  private readonly org = inject(OrgContext);
  private readonly router = inject(Router);

  protected readonly clients = signal<Client[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly duplicating = signal(false);
  protected newPhase = '';

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.orgApi.clients(orgId).subscribe({ next: (c) => this.clients.set(c) });
      }
    });
  }

  save(input: ProjectInput): void {
    const project = this.store.project();
    if (!project) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.update(project.id, input).subscribe({
      next: (p) => {
        this.store.project.set(p);
        // A new end date changes which tasks are due after it.
        this.store.refreshSchedule();
        this.busy.set(false);
        this.saved.set(true);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(messageOf(err));
      },
    });
  }

  reset(): void {
    this.store.project.update((p) => (p ? { ...p } : p));
  }

  addPhase(): void {
    const project = this.store.project();
    const name = this.newPhase.trim();
    if (!project || !name) {
      return;
    }
    this.api.createPhase(project.id, { name }).subscribe({
      next: () => {
        this.newPhase = '';
        this.store.refreshPhases();
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  rename(phase: Phase, name: string): void {
    if (!name.trim() || name === phase.name) {
      return;
    }
    this.api.updatePhase(phase.id, { ...phase, name }).subscribe({ next: () => this.store.refreshPhases() });
  }

  move(phase: Phase, delta: number): void {
    const phases = [...this.store.phases()];
    const index = phases.findIndex((p) => p.id === phase.id);
    const other = phases[index + delta];
    if (!other) {
      return;
    }
    this.api.updatePhase(phase.id, { ...phase, position: index + delta }).subscribe();
    this.api.updatePhase(other.id, { ...other, position: index }).subscribe({ next: () => this.store.refreshPhases() });
  }

  removePhase(phase: Phase): void {
    if (!confirm(`Remove the phase "${phase.name}"? Its tasks stay in the project.`)) {
      return;
    }
    this.api.deletePhase(phase.id).subscribe({
      next: () => {
        this.store.refreshPhases();
        this.store.reloadTasks();
      },
    });
  }

  openCopy(id: number): void {
    this.duplicating.set(false);
    void this.router.navigate(['/projects', id]);
  }

  remove(): void {
    const project = this.store.project();
    if (!project || !confirm(`Delete ${project.name}? This can't be undone.`)) {
      return;
    }
    this.api.delete(project.id).subscribe({
      next: () => void this.router.navigate(['/projects']),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
