import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Observable } from 'rxjs';
import { messageOf } from '../../core/errors';
import { Baseline, Client, Phase } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi, ProjectInput } from '../../core/project.api';
import { formatDate } from '../../shared/dates';
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

    <section class="card" aria-labelledby="st-baselines">
      <h2 id="st-baselines">Baselines</h2>
      <p class="muted">
        A baseline keeps the dates of every task and phase as they are now, to compare the plan with later. It never changes
        afterwards. The timeline draws the reference baseline under the bars. A project keeps up to {{ maxBaselines }}.
      </p>
      @if (baselineError()) {
        <p class="alert error" role="alert">{{ baselineError() }}</p>
      }
      @if (store.baselines().length) {
        <ul class="phase-list baseline-list">
          @for (b of store.baselines(); track b.id) {
            <li>
              <input [ngModel]="b.name" (change)="renameBaseline(b, $any($event.target).value)" [attr.aria-label]="'Name of ' + b.name" />
              <span class="muted small grow">
                {{ b.tasks }} task{{ b.tasks === 1 ? '' : 's' }}, saved {{ formatDate(b.createdAt.slice(0, 10)) }}{{ b.createdByName ? ' by ' + b.createdByName : '' }}
              </span>
              @if (b.reference) {
                <span class="badge">Reference</span>
              } @else {
                <button type="button" class="small" (click)="makeReference(b)">Make reference</button>
              }
              <button type="button" class="small danger" (click)="removeBaseline(b)">Delete</button>
            </li>
          }
        </ul>
      }
      <form class="quick-add" (ngSubmit)="takeBaseline()">
        <input name="baseline" placeholder="Name, for example Signed off 3 March" [(ngModel)]="newBaseline" aria-label="Name of the new baseline"
          maxlength="120" />
        <button type="submit" class="small" [disabled]="!newBaseline.trim() || store.baselines().length >= maxBaselines">Save baseline</button>
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
  protected newBaseline = '';
  protected readonly baselineError = signal<string | null>(null);
  /** The API's limit, BaselineService.MAX_PER_PROJECT. The API refuses an eleventh with its own message too. */
  protected readonly maxBaselines = 10;
  protected readonly formatDate = formatDate;

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
        // And how the project's end compares with the baseline.
        this.store.refreshComparison();
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

  takeBaseline(): void {
    const project = this.store.project();
    const name = this.newBaseline.trim();
    if (!project || !name) {
      return;
    }
    this.baselineRequest(this.api.takeBaseline(project.id, name), () => (this.newBaseline = ''));
  }

  renameBaseline(baseline: Baseline, name: string): void {
    if (!name.trim() || name === baseline.name) {
      return;
    }
    this.baselineRequest(this.api.updateBaseline(baseline.id, { name }));
  }

  /** The reference is what the timeline compares with when the project is opened. */
  makeReference(baseline: Baseline): void {
    this.baselineRequest(this.api.updateBaseline(baseline.id, { reference: true }), () => this.store.compareWith(baseline.id));
  }

  removeBaseline(baseline: Baseline): void {
    if (!confirm(`Delete the baseline "${baseline.name}"? The plan does not change.`)) {
      return;
    }
    this.baselineRequest(this.api.deleteBaseline(baseline.id));
  }

  /** Sends a change to the baselines, then fetches the list again. A refusal is shown, and the list is fetched anyway. */
  private baselineRequest(request: Observable<unknown>, done?: () => void): void {
    this.baselineError.set(null);
    request.subscribe({
      next: () => {
        done?.();
        this.store.loadBaselines();
      },
      error: (err: unknown) => {
        this.baselineError.set(messageOf(err));
        this.store.loadBaselines();
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
