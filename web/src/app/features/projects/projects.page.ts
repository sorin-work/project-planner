import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { Client, ProjectListItem, ProjectStatus } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi, ProjectInput } from '../../core/project.api';
import { formatRange } from '../../shared/dates';
import { PROJECT_STATUSES, PROJECT_STATUS_LABELS, formatHours, formatPercent } from '../../shared/format';
import { HealthBadge } from '../../shared/health-badge';
import { ProgressBar } from '../../shared/progress-bar';
import { ProjectForm } from './project-form';

@Component({
  selector: 'app-projects-page',
  imports: [FormsModule, RouterLink, HealthBadge, ProgressBar, ProjectForm],
  template: `
    <div class="page-header">
      <h1>Projects</h1>
      @if (org.canManage() && !creating()) {
        <button class="primary" type="button" (click)="creating.set(true)">New project</button>
      }
    </div>

    @if (creating()) {
      <section class="card">
        <h2>New project</h2>
        <app-project-form [clients]="clients()" submitLabel="Create project" [busy]="saving()" [error]="formError()"
          (saved)="create($event)" (cancelled)="creating.set(false)" />
      </section>
    }

    <div class="filters">
      <label>Status
        <select [(ngModel)]="status" (ngModelChange)="load()">
          <option value="">All</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ statusLabels[s] }}</option>
          }
        </select>
      </label>
      @if (clients().length > 1) {
        <label>Client
          <select [(ngModel)]="clientId" (ngModelChange)="load()">
            <option [ngValue]="null">All</option>
            @for (c of clients(); track c.id) {
              <option [ngValue]="c.id">{{ c.name }}</option>
            }
          </select>
        </label>
      }
    </div>

    @if (error()) {
      <p class="alert error">{{ error() }}</p>
    }

    <div class="table-wrap card">
      <table class="table">
        <thead>
          <tr>
            <th>Project</th>
            <th>Client</th>
            <th>Status</th>
            <th>Dates</th>
            <th class="num">Progress</th>
            <th class="num">Hours burn</th>
            <th>Health</th>
          </tr>
        </thead>
        <tbody>
          @for (item of visible(); track item.project.id) {
            <tr class="clickable" (click)="open(item.project.id)">
              <td>
                <span class="swatch" [style.background]="item.project.color"></span>
                <a [routerLink]="['/projects', item.project.id]" (click)="$event.stopPropagation()">{{ item.project.name }}</a>
                <span class="muted small code">{{ item.project.code }}</span>
              </td>
              <td>{{ item.project.clientName || 'Internal' }}</td>
              <td>{{ statusLabels[item.project.status] }}</td>
              <td class="nowrap">{{ formatRange(item.project.startDate, item.project.endDate) }}</td>
              <td class="num">
                <app-progress-bar [value]="item.health.progressPct" />
                <span class="small">{{ formatPercent(item.health.progressPct) }}</span>
              </td>
              <td class="num">
                {{ formatPercent(item.health.hoursBurnPct) }}
                @if (item.project.budgetHours) {
                  <span class="muted small">of {{ formatHours(item.project.budgetHours) }}</span>
                }
              </td>
              <td>
                <app-health-badge [status]="item.health.status" />
                @if (item.health.overdueTasks) {
                  <span class="danger small">{{ item.health.overdueTasks }} overdue</span>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">{{ loading() ? 'Loading…' : 'No projects yet.' }}</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectsPage {
  private readonly api = inject(ProjectApi);
  private readonly orgApi = inject(OrgApi);
  private readonly router = inject(Router);
  protected readonly org = inject(OrgContext);

  protected readonly items = signal<ProjectListItem[]>([]);
  protected readonly clients = signal<Client[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected status: ProjectStatus | '' = '';
  protected clientId: number | null = null;

  protected readonly visible = computed(() => this.items());
  protected readonly statuses = PROJECT_STATUSES;
  protected readonly statusLabels = PROJECT_STATUS_LABELS;
  protected readonly formatRange = formatRange;
  protected readonly formatPercent = formatPercent;
  protected readonly formatHours = formatHours;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.load();
        this.orgApi.clients(orgId).subscribe({ next: (c) => this.clients.set(c) });
      }
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.list(this.org.id(), { status: this.status, clientId: this.clientId }).subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.loading.set(false);
      },
    });
  }

  create(input: ProjectInput): void {
    this.saving.set(true);
    this.formError.set(null);
    this.api.create(this.org.id(), input).subscribe({
      next: (project) => {
        this.saving.set(false);
        void this.router.navigate(['/projects', project.id]);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.formError.set(messageOf(err));
      },
    });
  }

  open(id: number): void {
    void this.router.navigate(['/projects', id]);
  }
}
