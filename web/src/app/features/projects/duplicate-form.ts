import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { formatBytes } from '../project/attachment-files';
import { Client, Project } from '../../core/models';
import { ProjectApi } from '../../core/project.api';
import { copySummary, duplicateBody } from './duplicate';

/**
 * Copies the plan of a project into a new one. Only what the copy has to be told
 * is asked for: a name, a code, a client, a start date and whether people keep
 * their tasks. The API proposes the name, a code that is free and a start date.
 */
@Component({
  selector: 'app-duplicate-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="form-grid" novalidate>
      @if (summary(); as text) {
        <p class="muted span-2">{{ text }}</p>
      }
      <div class="field span-2">
        <label for="df-name">Name</label>
        <input id="df-name" formControlName="name" />
        @if (form.controls.name.touched && form.controls.name.invalid) {
          <p class="field-error">Give the copy a name</p>
        }
      </div>
      <div class="field">
        <label for="df-code">Code</label>
        <input id="df-code" formControlName="code" />
        @if (form.controls.code.touched && form.controls.code.invalid) {
          <p class="field-error">Up to 16 letters, digits or dashes</p>
        }
      </div>
      <div class="field">
        <label for="df-client">Client</label>
        <select id="df-client" formControlName="clientId">
          <option [ngValue]="null">Internal (no client)</option>
          @for (c of clients(); track c.id) {
            <option [ngValue]="c.id">{{ c.name }}{{ c.id === source().clientId ? ' (as in the source)' : '' }}</option>
          }
        </select>
      </div>
      <div class="field">
        <label for="df-start">Start</label>
        <input id="df-start" type="date" formControlName="startDate" />
        @if (form.controls.startDate.touched && form.controls.startDate.invalid) {
          <p class="field-error">Pick the day the copied plan begins on</p>
        }
      </div>
      <div class="field">
        <span class="label">Assignees</span>
        <label class="checkbox" for="df-assignees">
          <input id="df-assignees" type="checkbox" formControlName="keepAssignees" />
          Keep who each task is assigned to
        </label>
      </div>
      @if (files(); as f) {
        <div class="field">
          <span class="label">Files</span>
          <label class="checkbox" for="df-files">
            <input id="df-files" type="checkbox" formControlName="copyAttachments" />
            Copy the {{ f }} attached to the tasks
          </label>
        </div>
      }
      @if (error()) {
        <p class="alert error span-2" role="alert">{{ error() }}</p>
      }
      <div class="actions span-2">
        <button type="button" (click)="cancelled.emit()">Cancel</button>
        <button type="submit" class="primary" [disabled]="busy()">Copy project</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DuplicateForm {
  private readonly api = inject(ProjectApi);

  /** The project to copy from. */
  readonly source = input.required<Pick<Project, 'id' | 'name' | 'clientId'>>();
  readonly clients = input<Client[]>([]);
  /** The copy was made. */
  readonly created = output<Project>();
  readonly cancelled = output();

  protected readonly summary = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly form = inject(FormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    code: ['', [Validators.required, Validators.pattern(/^\s*[A-Za-z0-9][A-Za-z0-9-]{0,15}\s*$/)]],
    clientId: [null as number | null],
    startDate: ['', Validators.required],
    keepAssignees: [false],
    copyAttachments: [false],
  });

  /** "3 files, 2.1 MB" when the source has files attached, which are only copied when asked for. */
  protected readonly files = signal<string | null>(null);

  constructor() {
    // Ask what to propose whenever the source changes. The answer never overwrites
    // a form for another source that was picked in the meantime.
    effect(() => {
      const source = this.source();
      this.summary.set(null);
      this.error.set(null);
      this.files.set(null);
      this.form.reset({ name: '', code: '', clientId: null, startDate: '', keepAssignees: false, copyAttachments: false });
      this.api.duplicateDefaults(source.id).subscribe({
        next: (defaults) => {
          if (this.source().id === source.id) {
            this.form.reset({
              name: defaults.name, code: defaults.code, clientId: null, startDate: defaults.startDate, keepAssignees: false, copyAttachments: false,
            });
            this.files.set(defaults.attachments
              ? `${defaults.attachments} file${defaults.attachments === 1 ? '' : 's'} (${formatBytes(defaults.attachmentBytes)})`
              : null);
            this.summary.set(copySummary(source.name, defaults));
          }
        },
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.duplicate(this.source().id, duplicateBody(this.form.getRawValue())).subscribe({
      next: (project) => {
        this.busy.set(false);
        this.created.emit(project);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(messageOf(err));
      },
    });
  }
}
