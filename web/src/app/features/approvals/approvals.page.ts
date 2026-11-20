import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { MissingTimesheet, MissingTimesheets, TimesheetSummary, Week } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { TimeApi } from '../../core/time.api';
import { addDays, dayName, formatDate } from '../../shared/dates';
import { TIMESHEET_LABELS, formatMinutes, formatMoney } from '../../shared/format';
import { buildGrid } from '../time/timesheet-grid';
import {
  APPROVALS_TABS,
  ApprovalsTab,
  canRemind,
  groupMissing,
  loggedOfCapacity,
  paramOfTab,
  remindAllIds,
  remindSummary,
  tabOfParam,
} from './missing-timesheets';

interface MissingSection {
  key: 'late' | 'noTime';
  title: string;
  hint: string | null;
  empty: string;
  people: MissingTimesheet[];
}

@Component({
  selector: 'app-approvals-page',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page-header">
      <h1>Timesheet approvals</h1>
      <div class="segmented" role="group" aria-label="Status">
        @for (t of tabs; track t) {
          <button type="button" [class.active]="tab() === t" [attr.aria-pressed]="tab() === t" (click)="setTab(t)">
            {{ tabLabels[t] }}
          </button>
        }
      </div>
    </div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="grid-2 wide-right">
      <section class="card">
        @if (tab() !== 'MISSING') {
          <ul class="sheet-list">
            @for (s of sheets(); track s.id) {
              <li>
                <button type="button" class="row-button" [class.selected]="selected()?.timesheetId === s.id" (click)="open(s)">
                  <span class="grow"><strong>{{ s.userName }}</strong><br /><span class="muted small">Week of {{ formatDate(s.weekStart) }}</span></span>
                  <span class="right">{{ formatMinutes(s.totalMinutes) }}<br />
                    <span class="muted small">{{ money(s.billableAmount) }} billable</span></span>
                </button>
              </li>
            } @empty {
              <li class="muted">{{ tab() === 'SUBMITTED' ? 'Nothing waiting for approval.' : 'No timesheets.' }}</li>
            }
          </ul>
        } @else if (missing(); as m) {
          <div class="week-nav missing-nav">
            <button type="button" (click)="shiftWeek(-1)" aria-label="Previous week">‹</button>
            <span class="week-label">Week of {{ formatDate(m.weekStart) }}</span>
            <button type="button" (click)="shiftWeek(1)" [disabled]="m.weekStart >= m.latestWeekStart" aria-label="Next week">›</button>
          </div>
          @if (notice()) {
            <p class="alert info" role="status">{{ notice() }}</p>
          }
          @for (section of sections(); track section.key) {
            <div class="list-header">
              <h2>{{ section.title }}</h2>
              @if (section.key === 'late') {
                <button type="button" class="small" [disabled]="!remindAll().length || sending()" (click)="remind(remindAll())">
                  Remind all
                </button>
              }
            </div>
            @if (section.hint) {
              <p class="muted small">{{ section.hint }}</p>
            }
            <ul class="sheet-list">
              @for (p of section.people; track p.userId) {
                <li class="missing-row">
                  <button type="button" class="row-button" [class.selected]="selected()?.userId === p.userId" (click)="openWeek(p)">
                    <span class="grow"><strong>{{ p.userName }}</strong><br /><span class="muted small">{{ labels[p.status] }}</span></span>
                    <span class="right">{{ loggedOfCapacity(p) }}</span>
                  </button>
                  @if (p.userId === myId()) {
                    <a class="small" routerLink="/time" [queryParams]="{ week: m.weekStart }">Your week</a>
                  } @else {
                    <button type="button" class="small" [disabled]="!canRemind(p, myId()) || sending()" (click)="remind([p.userId])"
                      [attr.aria-label]="(p.remindedToday ? 'Reminded today: ' : 'Remind ') + p.userName">
                      {{ p.remindedToday ? 'Reminded today' : 'Remind' }}
                    </button>
                  }
                </li>
              } @empty {
                <li class="muted">{{ section.empty }}</li>
              }
            </ul>
          }
        }
      </section>
      <section class="card">
        @if (selected(); as w) {
          <h2>{{ w.userName }} · week of {{ formatDate(w.weekStart) }}</h2>
          <p class="muted">{{ formatMinutes(w.totalMinutes) }} total, {{ formatMinutes(w.billableMinutes) }} billable · {{ labels[w.status] }}</p>
          @if (grid(); as g) {
            <div class="table-wrap">
              <table class="table timesheet-grid">
                <thead><tr><th>Project / task</th>
                  @for (d of g.dates; track d) { <th class="num">{{ dayName(d) }}</th> }
                  <th class="num">Total</th></tr></thead>
                <tbody>
                  @for (row of g.rows; track row.key) {
                    <tr>
                      <td><span class="code">{{ row.projectCode }}</span> {{ row.taskTitle || row.projectName }}</td>
                      @for (m of row.days; track $index) { <td class="num">{{ m ? formatMinutes(m) : '' }}</td> }
                      <td class="num">{{ formatMinutes(row.total) }}</td>
                    </tr>
                  } @empty {
                    <tr><td colspan="9" class="muted">No time logged this week.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          }
          @if (w.status === 'SUBMITTED') {
            <div class="review-actions">
              <label for="note">Note (sent with "send back")</label>
              <input id="note" [(ngModel)]="note" placeholder="What needs fixing?" />
              <div class="actions">
                <button type="button" (click)="reject()">Send back</button>
                <button type="button" class="primary" (click)="approve()">Approve</button>
              </div>
            </div>
          }
        } @else {
          <p class="muted">{{ tab() === 'MISSING' ? 'Choose a person to see their week.' : 'Choose a timesheet to review.' }}</p>
        }
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalsPage {
  private readonly api = inject(TimeApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly org = inject(OrgContext);

  protected readonly tabs = APPROVALS_TABS;
  protected readonly tabLabels: Record<ApprovalsTab, string> = { ...TIMESHEET_LABELS, MISSING: 'Not submitted' };
  /** The tab lives in ?tab= so that the dashboard can link to the people who have not submitted. */
  protected readonly tab = toSignal(this.route.queryParamMap.pipe(map((q) => tabOfParam(q.get('tab')))), {
    initialValue: tabOfParam(this.route.snapshot.queryParamMap.get('tab')),
  });
  protected readonly sheets = signal<TimesheetSummary[]>([]);
  /** The week of the "Not submitted" tab; null is the last week that is over, which only the API knows. */
  protected readonly missingWeek = signal<string | null>(null);
  protected readonly missing = signal<MissingTimesheets | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly selected = signal<Week | null>(null);
  protected readonly grid = signal<ReturnType<typeof buildGrid> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected note = '';

  protected readonly myId = computed(() => this.auth.user()?.id ?? null);
  protected readonly remindAll = computed(() => remindAllIds(this.missing()?.people ?? [], this.myId()));
  protected readonly sections = computed<MissingSection[]>(() => {
    const groups = groupMissing(this.missing()?.people ?? []);
    const late: MissingSection = {
      key: 'late',
      title: 'Not submitted',
      hint: null,
      empty: 'Everybody who logged time in this week has submitted it.',
      people: groups.late,
    };
    const noTime: MissingSection = {
      key: 'noTime',
      title: 'No time logged',
      hint: 'They may have been away. "Remind all" leaves them out.',
      empty: '',
      people: groups.noTime,
    };
    return groups.noTime.length ? [late, noTime] : [late];
  });

  protected readonly labels = TIMESHEET_LABELS;
  protected readonly formatDate = formatDate;
  protected readonly formatMinutes = formatMinutes;
  protected readonly dayName = dayName;
  protected readonly loggedOfCapacity = loggedOfCapacity;
  protected readonly canRemind = canRemind;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      const tab = this.tab();
      const week = this.missingWeek();
      this.selected.set(null);
      this.notice.set(null);
      if (orgId === null) {
        return;
      }
      if (tab === 'MISSING') {
        this.loadMissing(orgId, week);
      } else {
        this.api.timesheets(orgId, tab).subscribe({
          next: (s) => this.sheets.set(s),
          error: (err: unknown) => this.error.set(messageOf(err)),
        });
      }
    });
  }

  money(amount: number): string {
    return formatMoney(amount, this.org.currency());
  }

  setTab(tab: ApprovalsTab): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: { tab: paramOfTab(tab) }, queryParamsHandling: 'merge' });
  }

  shiftWeek(weeks: number): void {
    const shown = this.missing();
    if (shown) {
      this.missingWeek.set(addDays(shown.weekStart, weeks * 7));
    }
  }

  open(sheet: TimesheetSummary): void {
    this.note = '';
    this.api.timesheet(this.org.id(), sheet.id).subscribe({
      next: (w) => this.show(w),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  /** Shows what somebody has logged so far in the week they have not submitted. */
  openWeek(person: MissingTimesheet): void {
    const shown = this.missing();
    if (!shown) {
      return;
    }
    this.api.week(this.org.id(), shown.weekStart, person.userId).subscribe({
      next: (w) => this.show(w),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remind(userIds: number[]): void {
    const shown = this.missing();
    if (!shown || !userIds.length || this.sending()) {
      return;
    }
    const orgId = this.org.id();
    this.sending.set(true);
    this.error.set(null);
    this.api.remind(orgId, shown.weekStart, userIds).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.notice.set(remindSummary(result, shown.people));
        this.loadMissing(orgId, this.missingWeek());
      },
      error: (err: unknown) => {
        this.sending.set(false);
        this.error.set(messageOf(err));
      },
    });
  }

  approve(): void {
    this.review(false);
  }

  reject(): void {
    this.review(true);
  }

  private review(reject: boolean): void {
    const w = this.selected();
    if (!w?.timesheetId) {
      return;
    }
    const orgId = this.org.id();
    const request = reject ? this.api.reject(orgId, w.timesheetId, this.note) : this.api.approve(orgId, w.timesheetId);
    request.subscribe({
      next: (week) => {
        this.show(week);
        this.sheets.update((all) => all.filter((s) => s.id !== w.timesheetId));
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private loadMissing(orgId: number, week: string | null): void {
    this.api.missing(orgId, week).subscribe({
      next: (m) => {
        // An answer for a week that is no longer the one asked for would put the wrong people on screen.
        if (this.missingWeek() === week) {
          this.missing.set(m);
        }
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private show(week: Week): void {
    this.selected.set(week);
    this.grid.set(buildGrid(week.weekStart, week.entries));
  }
}
