import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { OrgContext } from '../../core/org-context.service';
import { MissingTimesheet, MissingTimesheets } from '../../core/models';
import { ApprovalsPage } from './approvals.page';

const MISSING_URL = '/api/orgs/7/timesheets/missing';

function person(userId: number, userName: string, totalMinutes: number, remindedToday = false): MissingTimesheet {
  return { userId, userName, status: 'DRAFT', totalMinutes, capacityHours: 40, away: false, remindedToday };
}

function missing(weekStart: string, people: MissingTimesheet[]): MissingTimesheets {
  return { weekStart, latestWeekStart: '2026-03-02', people };
}

describe('ApprovalsPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'approvals', component: ApprovalsPage }]),
      ],
    });
    TestBed.inject(AuthService).me.set({
      user: { id: 5, email: 'maya@example.com', name: 'Maya Collins', handle: 'maya' },
      organisations: [
        { organisationId: 7, organisationName: 'Brightline Studio', role: 'OWNER', currency: 'EUR' },
        { organisationId: 8, organisationName: 'Side Project', role: 'OWNER', currency: 'USD' },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function openNotSubmitted(people: MissingTimesheet[]) {
    const harness = await RouterTestingHarness.create('/approvals?tab=missing');
    const first = http.expectOne((r) => r.url === MISSING_URL);
    expect(first.request.params.has('weekStart')).toBe(false);
    first.flush(missing('2026-03-02', people));
    await harness.fixture.whenStable();
    return { harness, el: harness.routeNativeElement as HTMLElement };
  }

  function button(el: HTMLElement, label: string): HTMLButtonElement {
    const found = [...el.querySelectorAll('button')].find((b) => b.textContent.trim() === label);
    if (!found) {
      throw new Error(`No button "${label}"`);
    }
    return found;
  }

  it('lists who has not submitted and reminds everybody who logged time', async () => {
    const people = [person(1, 'Tom Becker', 2250), person(2, 'Samir Haddad', 1800), person(3, 'Lena Novak', 0), person(5, 'Maya Collins', 300)];
    const { harness, el } = await openNotSubmitted(people);

    expect(el.querySelector('.segmented .active')?.textContent).toContain('Not submitted');
    expect(el.textContent).toContain('37h 30m of 40h');
    expect(el.textContent).toContain('No time logged');
    // Nobody reminds themselves: the manager's own row links to their timesheet instead.
    expect(el.querySelector('.missing-row a')?.textContent).toContain('Your week');
    expect(el.querySelectorAll('.missing-row button.small')).toHaveLength(3);

    button(el, 'Remind all').click();
    const sent = http.expectOne('/api/orgs/7/timesheets/remind');
    expect(sent.request.body).toEqual({ weekStart: '2026-03-02', userIds: [1, 2] });
    sent.flush({ weekStart: '2026-03-02', reminded: [1, 2], skipped: [] });
    http.expectOne((r) => r.url === MISSING_URL)
      .flush(missing('2026-03-02', [person(1, 'Tom Becker', 2250, true), person(2, 'Samir Haddad', 1800, true), people[2], people[3]]));
    await harness.fixture.whenStable();

    expect(el.querySelector('[role=status]')?.textContent).toContain('Reminded Tom Becker and Samir Haddad.');
    expect(button(el, 'Remind all').disabled).toBe(true);
    expect([...el.querySelectorAll<HTMLButtonElement>('.missing-row button.small')].map((b) => [b.textContent.trim(), b.disabled])).toEqual([
      ['Reminded today', true],
      ['Reminded today', true],
      ['Remind', false],
    ]);
  });

  it('drops the week of a person when it arrives after the list has moved on', async () => {
    const { harness, el } = await openNotSubmitted([person(1, 'Tom Becker', 2250), person(2, 'Samir Haddad', 1800)]);
    const rows = el.querySelectorAll<HTMLButtonElement>('.missing-row .row-button');
    const answer = (userId: number, userName: string) => ({
      userId, userName, weekStart: '2026-03-02', status: 'DRAFT', entries: [], days: [], totalMinutes: 0, billableMinutes: 0,
    });

    // Tom is asked for first, but Samir is the one chosen last.
    rows[0].click();
    rows[1].click();
    const [tom, samir] = http.match((r) => r.url === '/api/orgs/7/timesheets/week');
    samir.flush(answer(2, 'Samir Haddad'));
    tom.flush(answer(1, 'Tom Becker'));
    await harness.fixture.whenStable();
    expect(el.querySelector('.grid-2 > section:last-child h2')?.textContent).toContain('Samir Haddad');

    // The same when the week changes before the answer is there.
    rows[0].click();
    const late = http.expectOne((r) => r.url === '/api/orgs/7/timesheets/week');
    el.querySelector<HTMLButtonElement>('[aria-label="Previous week"]')!.click();
    await harness.fixture.whenStable();
    http.expectOne((r) => r.url === MISSING_URL).flush(missing('2026-02-23', [person(2, 'Samir Haddad', 600)]));
    late.flush(answer(1, 'Tom Becker'));
    await harness.fixture.whenStable();

    expect(el.textContent).toContain('Choose a person to see their week.');
    expect(el.textContent).not.toContain('Tom Becker');
  });

  it('starts over in another organisation, and takes the list away when it can not be loaded', async () => {
    const { harness, el } = await openNotSubmitted([person(1, 'Tom Becker', 2250)]);
    el.querySelector<HTMLButtonElement>('[aria-label="Previous week"]')!.click();
    await harness.fixture.whenStable();
    http.expectOne((r) => r.url === MISSING_URL).flush(missing('2026-02-23', [person(1, 'Tom Becker', 600)]));
    await harness.fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.missing-row .row-button')!.click();
    const week = http.expectOne((r) => r.url === '/api/orgs/7/timesheets/week');

    // The switcher in the side bar changes the organisation without leaving the page.
    TestBed.inject(OrgContext).select(8);
    await harness.fixture.whenStable();
    const other = http.expectOne((r) => r.url === '/api/orgs/8/timesheets/missing');
    // Neither the week that was chosen nor the people of the first organisation come along.
    expect(other.request.params.has('weekStart')).toBe(false);
    expect(el.textContent).not.toContain('Tom Becker');
    week.flush({ userId: 1, userName: 'Tom Becker', weekStart: '2026-02-23', status: 'DRAFT', entries: [], days: [], totalMinutes: 0, billableMinutes: 0 });
    await harness.fixture.whenStable();
    expect(el.textContent).not.toContain('Tom Becker');

    other.flush({ status: 500, code: 'internal_error', message: 'Something went wrong on our side' }, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Something went wrong on our side');
    expect(el.querySelectorAll('.missing-row')).toHaveLength(0);
  });

  it('approves the ticked weeks as they were on screen and names the ones left out', async () => {
    const queue = [
      { id: 11, userId: 1, userName: 'Tom Becker', weekStart: '2024-03-04', status: 'SUBMITTED', submittedAt: '2024-03-11T08:00:00.123456Z',
        totalMinutes: 2400, billableMinutes: 1800, billableAmount: 2250 },
      { id: 12, userId: 2, userName: 'Samir Haddad', weekStart: '2024-03-04', status: 'SUBMITTED', submittedAt: '2024-03-11T09:00:00Z',
        totalMinutes: 1200, billableMinutes: 600, billableAmount: 750 },
    ];
    const harness = await RouterTestingHarness.create('/approvals');
    http.expectOne((r) => r.url === '/api/orgs/7/timesheets').flush(queue);
    await harness.fixture.whenStable();
    const el = harness.routeNativeElement as HTMLElement;
    expect(button(el, 'Approve selected').disabled).toBe(true);

    const boxes = el.querySelectorAll<HTMLInputElement>('.approve-row input[type=checkbox]');
    expect(boxes).toHaveLength(2);
    expect(boxes[1].getAttribute('aria-label')).toBe('Select Samir Haddad, week of 4 Mar 2024');
    boxes[1].click();
    await harness.fixture.whenStable();
    button(el, 'Approve selected (1)').click();
    const sent = http.expectOne('/api/orgs/7/timesheets/approve');
    expect(sent.request.body).toEqual({ sheets: [{ id: 12, submittedAt: '2024-03-11T09:00:00Z' }] });
    sent.flush({ approved: [], skipped: [{ timesheetId: 12, reason: 'CHANGED' }] });
    // The queue comes again, with Samir's new submission in it.
    http.expectOne((r) => r.url === '/api/orgs/7/timesheets').flush([queue[0], { ...queue[1], submittedAt: '2024-03-12T07:00:00Z' }]);
    await harness.fixture.whenStable();

    expect(el.querySelector('[role=status]')?.textContent).toContain('No weeks approved.');
    expect(el.querySelector('.left-out')?.textContent).toContain('Samir Haddad, week of 4 Mar 2024: withdrawn and submitted again');
    expect(boxes[1].checked).toBe(true);

    button(el, 'Approve all').click();
    const all = http.expectOne('/api/orgs/7/timesheets/approve');
    expect(all.request.body.sheets).toEqual([
      { id: 11, submittedAt: '2024-03-11T08:00:00.123456Z' },
      { id: 12, submittedAt: '2024-03-12T07:00:00Z' },
    ]);
    all.flush({ approved: [11, 12], skipped: [] });
    http.expectOne((r) => r.url === '/api/orgs/7/timesheets').flush([]);
    await harness.fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Approved 2 weeks.');
    expect(el.textContent).toContain('Nothing waiting for approval.');
  });

  it('goes back through earlier weeks but not past the last one that is over', async () => {
    const { harness, el } = await openNotSubmitted([person(1, 'Tom Becker', 2250)]);
    const next = el.querySelector<HTMLButtonElement>('[aria-label="Next week"]')!;
    expect(next.disabled).toBe(true);

    el.querySelector<HTMLButtonElement>('[aria-label="Previous week"]')!.click();
    await harness.fixture.whenStable();
    const earlier = http.expectOne((r) => r.url === MISSING_URL);
    expect(earlier.request.params.get('weekStart')).toBe('2026-02-23');
    earlier.flush(missing('2026-02-23', []));
    await harness.fixture.whenStable();

    expect(next.disabled).toBe(false);
    expect(el.textContent).toContain('Everybody who logged time in this week has submitted it.');
  });
});
