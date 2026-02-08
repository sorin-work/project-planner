import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, Project, ProjectStatus } from '../../core/models';
import { ProjectInput } from '../../core/project.api';
import { PROJECT_STATUSES, PROJECT_STATUS_LABELS } from '../../shared/format';

const COLORS = ['#4f6bed', '#2f9e72', '#c2410c', '#7c3aed', '#0e7490', '#d97706', '#be185d', '#475569'];

@Component({
  selector: 'app-project-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="form-grid" novalidate>
      <div class="field span-2">
        <label for="pf-name">Name</label>
        <input id="pf-name" formControlName="name" />
        @if (form.controls.name.touched && form.controls.name.invalid) {
          <p class="field-error">Give the project a name</p>
        }
      </div>
      <div class="field">
        <label for="pf-code">Code</label>
        <input id="pf-code" formControlName="code" placeholder="ACME-WEB" />
        @if (form.controls.code.touched && form.controls.code.invalid) {
          <p class="field-error">Up to 16 letters, digits or dashes</p>
        }
      </div>
      <div class="field">
        <label for="pf-client">Client</label>
        <select id="pf-client" formControlName="clientId">
          <option [ngValue]="null">Internal (no client)</option>
          @for (c of clients(); track c.id) {
            <option [ngValue]="c.id">{{ c.name }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="pf-status">Status</label>
        <select id="pf-status" formControlName="status">
          @for (s of statuses; track s) {
            <option [ngValue]="s">{{ statusLabels[s] }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="pf-start">Start</label>
        <input id="pf-start" type="date" formControlName="startDate" />
      </div>
      <div class="field">
        <label for="pf-end">End</label>
        <input id="pf-end" type="date" formControlName="endDate" />
      </div>
      <div class="field">
        <label for="pf-hours">Budget (hours)</label>
        <input id="pf-hours" type="number" min="0" step="1" formControlName="budgetHours" />
      </div>
      <div class="field">
        <label for="pf-amount">Budget (money)</label>
        <input id="pf-amount" type="number" min="0" step="100" formControlName="budgetAmount" />
      </div>
      <div class="field span-2">
        <label for="pf-description">Description</label>
        <textarea id="pf-description" rows="3" formControlName="description"></textarea>
      </div>
      @if (error()) {
        <p class="alert error span-2">{{ error() }}</p>
      }
      <div class="actions span-2">
        <button type="button" (click)="cancelled.emit()">Cancel</button>
        <button type="submit" class="primary" [disabled]="busy()">{{ submitLabel() }}</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectForm {
  readonly project = input<Project | null>(null);
  readonly clients = input<Client[]>([]);
  readonly submitLabel = input('Save');
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly saved = output<ProjectInput>();
  readonly cancelled = output();

  protected readonly statuses = PROJECT_STATUSES;
  protected readonly statusLabels = PROJECT_STATUS_LABELS;
  protected readonly colors = COLORS;
  protected readonly ready = signal(false);

  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    code: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9-]{0,15}$/)]],
    clientId: [null as number | null],
    status: ['PLANNED' as ProjectStatus],
    color: [COLORS[0]],
    startDate: [''],
    endDate: [''],
    budgetHours: [null as number | null],
    budgetAmount: [null as number | null],
    hourlyRate: [null as number | null],
    description: [''],
  });

  constructor() {
    effect(() => {
      const p = this.project();
      if (p) {
        this.form.reset({
          name: p.name,
          code: p.code,
          clientId: p.clientId ?? null,
          status: p.status,
          color: p.color,
          startDate: p.startDate ?? '',
          endDate: p.endDate ?? '',
          budgetHours: p.budgetHours ?? null,
          budgetAmount: p.budgetAmount ?? null,
          hourlyRate: p.hourlyRate ?? null,
          description: p.description ?? '',
        });
      }
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saved.emit({
      name: v.name ?? '',
      code: (v.code ?? '').toUpperCase(),
      clientId: v.clientId,
      status: v.status ?? 'PLANNED',
      color: v.color ?? COLORS[0],
      startDate: v.startDate || null,
      endDate: v.endDate || null,
      budgetHours: numberOrNull(v.budgetHours),
      budgetAmount: numberOrNull(v.budgetAmount),
      hourlyRate: numberOrNull(v.hourlyRate),
      description: v.description || null,
    });
  }
}

function numberOrNull(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return Number(value);
}
