import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CalendarApi } from '../../core/calendar.api';
import { messageOf } from '../../core/errors';
import { Holiday } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { addDays, dayName, formatDate, isWeekend, today } from '../../shared/dates';

/**
 * The organisation's holidays, for owners and managers. A holiday is left out of every count
 * of working days: plans, the timeline, progress and capacity. Adding one moves no task.
 */
@Component({
  selector: 'app-holidays-card',
  imports: [FormsModule],
  template: `
    <section class="card" aria-labelledby="holidays-title">
      <h2 id="holidays-title">Holidays</h2>
      <p class="muted small">
        Days nobody works. They are skipped when plans are moved and counted out of everybody's capacity.
        Adding one does not move any task: a task across it is a working day shorter.
      </p>
      @if (error()) {
        <p class="alert error" role="alert">{{ error() }}</p>
      }
      <form class="form-grid" (ngSubmit)="add()">
        <div class="field"><label for="h-date">Date</label>
          <input id="h-date" name="date" type="date" [(ngModel)]="date" required /></div>
        <div class="field"><label for="h-name">Name</label>
          <input id="h-name" name="name" [(ngModel)]="name" maxlength="120" required /></div>
        <div class="actions span-2"><button type="submit" class="primary" [disabled]="!date || !name.trim()">Add holiday</button></div>
      </form>
      @if (holidays().length) {
        <ul class="calendar-list">
          @for (h of holidays(); track h.id) {
            <li>
              <span>{{ dayName(h.date) }} {{ formatDate(h.date) }} · {{ h.name }}
                @if (isWeekend(h.date)) { <span class="muted small">(a weekend, changes nothing)</span> }
              </span>
              <button type="button" class="link" (click)="remove(h)" [attr.aria-label]="'Remove ' + h.name">Remove</button>
            </li>
          }
        </ul>
      } @else {
        <p class="muted">No holidays from a month ago on.</p>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HolidaysCard {
  private readonly api = inject(CalendarApi);
  private readonly org = inject(OrgContext);

  protected readonly holidays = signal<Holiday[]>([]);
  protected readonly error = signal<string | null>(null);
  protected date = '';
  protected name = '';
  protected readonly dayName = dayName;
  protected readonly formatDate = formatDate;
  protected readonly isWeekend = isWeekend;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      untracked(() => {
        this.holidays.set([]);
        if (orgId !== null) {
          this.load(orgId);
        }
      });
    });
  }

  add(): void {
    const orgId = this.org.id();
    this.error.set(null);
    this.api.addHoliday(orgId, { date: this.date, name: this.name.trim() }).subscribe({
      next: (holiday) => {
        this.holidays.update((all) => [...all, holiday].sort((a, b) => a.date.localeCompare(b.date)));
        this.date = '';
        this.name = '';
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remove(holiday: Holiday): void {
    this.error.set(null);
    this.api.deleteHoliday(this.org.id(), holiday.id).subscribe({
      next: () => this.holidays.update((all) => all.filter((h) => h.id !== holiday.id)),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private load(orgId: number): void {
    this.api.holidays(orgId, { from: addDays(today(), -31) }).subscribe({
      next: (list) => this.holidays.set(list),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
