import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { InboxApi } from '../../core/inbox.api';
import { messageOf } from '../../core/errors';
import { MemberWorkload, WeekLoad, Workload, WorkloadTask } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { addDays, formatDate, today, weekStart } from '../../shared/dates';
import { formatHours } from '../../shared/format';
import { capacityNote } from '../../shared/calendar-view';
import { loadLevel } from './workload-cell';

@Component({
  selector: 'app-workload-page',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-header">
      <h1>Workload</h1>
      <div class="week-nav">
        <button type="button" (click)="shift(-weeks)" aria-label="Earlier">‹</button>
        <span>{{ formatDate(from()) }} – {{ formatDate(lastDay()) }}</span>
        <button type="button" (click)="shift(weeks)" aria-label="Later">›</button>
        <select [(ngModel)]="weeks" (ngModelChange)="reload()" aria-label="Weeks to show">
          <option [ngValue]="4">4 weeks</option>
          <option [ngValue]="8">8 weeks</option>
          <option [ngValue]="12">12 weeks</option>
        </select>
      </div>
    </div>
    <p class="muted small">Open tasks in planned and active projects, each estimate spread evenly over its working days.</p>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    @if (data(); as d) {
      <div class="card table-wrap">
        <table class="table workload">
          <thead>
            <tr>
              <th>Person</th>
              @for (w of d.members[0]?.weeks ?? []; track w.weekStart) {
                <th class="num">{{ formatDate(w.weekStart) }}</th>
              }
              <th class="num" title="Open estimates without dates">Unscheduled</th>
            </tr>
          </thead>
          <tbody>
            @for (m of d.members; track m.userId) {
              <tr>
                <td><strong>{{ m.name }}</strong><br /><span class="muted small">{{ formatHours(m.capacityHours) }}/week</span></td>
                @for (w of m.weeks; track w.weekStart) {
                  <td class="num load-cell" [class]="'num load-cell load-' + level(w)">
                    <button type="button" class="cell-button" (click)="select(m, w)"
                      [attr.aria-label]="m.name + ', week of ' + w.weekStart + ': ' + w.allocatedHours + ' of ' + w.capacityHours + ' hours'
                        + (capacityNote(w) ? '. ' + capacityNote(w) : '')">
                      {{ w.allocatedHours ? formatHours(w.allocatedHours) : '' }}
                      @if (w.overallocated) { <span class="flag" title="Over capacity">!</span> }
                      @if (capacityNote(w); as note) { <span class="less" [title]="note" aria-hidden="true">−</span> }
                    </button>
                  </td>
                }
                <td class="num muted">{{ m.unscheduledHours ? formatHours(m.unscheduledHours) : '' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <p class="legend small">
        <span class="load-light">under 50%</span> <span class="load-good">50–85%</span> <span class="load-full">85–100%</span>
        <span class="load-over">over capacity</span> <span><span class="less">−</span> less capacity (holidays, time off)</span>
      </p>
      @if (selection(); as s) {
        <section class="card">
          <h2>{{ s.member.name }} · week of {{ formatDate(s.week.weekStart) }}</h2>
          <p class="muted">{{ formatHours(s.week.allocatedHours) }} allocated of {{ formatHours(s.week.capacityHours) }}
            @if (capacityNote(s.week); as note) { · {{ note }} }</p>
          <ul class="task-lines">
            @for (t of s.tasks; track t.id) {
              <li>
                <span class="swatch" [style.background]="t.projectColor"></span>
                <a [routerLink]="['/projects', t.projectId, 'timeline']" [queryParams]="{ task: t.id }">{{ t.title }}</a>
                <span class="muted small">{{ t.projectCode }} · {{ formatDate(t.startDate) }} – {{ formatDate(t.dueDate) }} · {{ formatHours(t.estimateHours) }}</span>
              </li>
            }
          </ul>
        </section>
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkloadPage {
  protected readonly capacityNote = capacityNote;
  private readonly api = inject(InboxApi);
  private readonly org = inject(OrgContext);

  protected readonly from = signal(weekStart(today()));
  protected weeks = 8;
  protected readonly data = signal<Workload | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly selection = signal<{ member: MemberWorkload; week: WeekLoad; tasks: WorkloadTask[] } | null>(null);
  private readonly tick = signal(0);

  protected readonly lastDay = computed(() => {
    this.tick();
    return addDays(this.from(), this.weeks * 7 - 1);
  });
  protected readonly formatDate = formatDate;
  protected readonly formatHours = formatHours;
  protected readonly level = loadLevel;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      const from = this.from();
      this.tick();
      if (orgId === null) {
        return;
      }
      this.api.workload(orgId, from, this.weeks).subscribe({
        next: (d) => {
          this.data.set(d);
          this.selection.set(null);
        },
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
    });
  }

  shift(weeks: number): void {
    this.from.update((d) => addDays(d, weeks * 7));
  }

  reload(): void {
    this.tick.update((t) => t + 1);
  }

  select(member: MemberWorkload, week: WeekLoad): void {
    const tasks = this.data()?.tasks.filter((t) => week.taskIds.includes(t.id)) ?? [];
    this.selection.set({ member, week, tasks });
  }
}
