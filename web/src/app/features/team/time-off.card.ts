import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { CalendarApi } from '../../core/calendar.api';
import { messageOf } from '../../core/errors';
import { Member, TimeOff } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { Holidays, NO_HOLIDAYS, addDays, today } from '../../shared/dates';
import { canChangeTimeOff, holidaySet, timeOffLabel, timeOffProblem } from '../../shared/calendar-view';

/**
 * Time off of the team, from a week ago on. People add and remove their own, owners and
 * managers anybody's. Time off lowers capacity on the workload page and moves no plan.
 */
@Component({
  selector: 'app-time-off-card',
  imports: [FormsModule],
  template: `
    <section class="card" aria-labelledby="time-off-title">
      <h2 id="time-off-title">Time off</h2>
      <p class="muted small">Days somebody is away. Their capacity on the workload page goes down; no task moves.</p>
      @if (error()) {
        <p class="alert error" role="alert">{{ error() }}</p>
      }
      <form class="form-grid" (ngSubmit)="add()">
        @if (org.canManage()) {
          <div class="field span-2"><label for="to-person">Person</label>
            <select id="to-person" name="person" [(ngModel)]="userId">
              @for (m of team(); track m.userId) { <option [ngValue]="m.userId">{{ m.name }}</option> }
            </select></div>
        }
        <div class="field"><label for="to-first">First day</label>
          <input id="to-first" name="first" type="date" [(ngModel)]="firstDay" required /></div>
        <div class="field"><label for="to-last">Last day</label>
          <input id="to-last" name="last" type="date" [(ngModel)]="lastDay" required /></div>
        <div class="field span-2"><label for="to-note">Note</label>
          <input id="to-note" name="note" [(ngModel)]="note" maxlength="500" /></div>
        <div class="actions span-2"><button type="submit" class="primary">Add time off</button></div>
      </form>
      @if (entries().length) {
        <ul class="calendar-list">
          @for (t of entries(); track t.id) {
            <li>
              <span><strong>{{ nameOf(t.userId) }}</strong> · {{ label(t) }}
                @if (t.note) { <span class="muted"> · {{ t.note }}</span> }
              </span>
              @if (canChange(t)) {
                <button type="button" class="link" (click)="remove(t)" [attr.aria-label]="'Remove time off of ' + nameOf(t.userId)">Remove</button>
              }
            </li>
          }
        </ul>
      } @else {
        <p class="muted">Nobody has time off from a week ago on.</p>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimeOffCard {
  private readonly api = inject(CalendarApi);
  private readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContext);

  /** The members of the organisation, from the page. */
  readonly members = input<Member[]>([]);

  protected readonly entries = signal<TimeOff[]>([]);
  private readonly holidays = signal<Holidays>(NO_HOLIDAYS);
  protected readonly error = signal<string | null>(null);
  protected readonly team = computed(() => this.members().filter((m) => m.role !== 'CLIENT'));
  protected userId: number | null = null;
  protected firstDay = '';
  protected lastDay = '';
  protected note = '';

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      untracked(() => {
        this.entries.set([]);
        this.userId = this.auth.user()?.id ?? null;
        if (orgId !== null && this.org.isTeam()) {
          this.load(orgId);
        }
      });
    });
  }

  protected nameOf(userId: number): string {
    return this.members().find((m) => m.userId === userId)?.name ?? 'Somebody who left';
  }

  protected label(entry: TimeOff): string {
    return timeOffLabel(entry, this.holidays());
  }

  protected canChange(entry: TimeOff): boolean {
    return canChangeTimeOff(entry, this.auth.user()?.id, this.org.canManage());
  }

  add(): void {
    const problem = timeOffProblem(this.firstDay, this.lastDay);
    this.error.set(problem);
    if (problem) {
      return;
    }
    const body = {
      userId: this.org.canManage() && this.userId !== null ? this.userId : undefined,
      firstDay: this.firstDay,
      lastDay: this.lastDay,
      note: this.note.trim() || null,
    };
    this.api.addTimeOff(this.org.id(), body).subscribe({
      next: (entry) => {
        this.entries.update((all) => [...all, entry].sort((a, b) => a.firstDay.localeCompare(b.firstDay)));
        this.firstDay = '';
        this.lastDay = '';
        this.note = '';
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remove(entry: TimeOff): void {
    this.error.set(null);
    this.api.deleteTimeOff(this.org.id(), entry.id).subscribe({
      next: () => this.entries.update((all) => all.filter((t) => t.id !== entry.id)),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private load(orgId: number): void {
    const from = addDays(today(), -7);
    forkJoin({ entries: this.api.timeOff(orgId, { from }), holidays: this.api.holidays(orgId, { from }) }).subscribe({
      next: ({ entries, holidays }) => {
        this.entries.set(entries);
        this.holidays.set(holidaySet(holidays));
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
