import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { TimeEntry, TimesheetStatus, Week } from '../../core/models';
import { TimesheetPage } from './timesheet.page';

const WEEK_URL = '/api/orgs/7/timesheets/week';
const ENTRIES_URL = '/api/orgs/7/time-entries';
// 2026-03-02 is a Monday.
const MONDAY = '2026-03-02';

const PROJECTS = [
  { project: { id: 1, code: 'WEB', name: 'Website', status: 'ACTIVE', clientId: 4 } },
  { project: { id: 2, code: 'ADM', name: 'Admin', status: 'ACTIVE' } },
  { project: { id: 3, code: 'OLD', name: 'Old site', status: 'DONE', clientId: 4 } },
];

function entry(id: number, date: string, minutes: number, extra: Partial<TimeEntry> = {}): TimeEntry {
  return {
    id, userId: 1, userName: 'Tom Becker', projectId: 1, projectName: 'Website', projectCode: 'WEB', taskId: 5, taskTitle: 'Design',
    date, minutes, billable: true, running: false, locked: false, ...extra,
  };
}

function week(start: string, entries: TimeEntry[] = [], status: TimesheetStatus = 'DRAFT'): Week {
  const total = entries.reduce((sum, e) => sum + e.minutes, 0);
  return { userId: 1, userName: 'Tom Becker', weekStart: start, status, entries, days: [], totalMinutes: total, billableMinutes: total };
}

describe('TimesheetPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 'time', component: TimesheetPage }])],
    });
    TestBed.inject(AuthService).me.set({
      user: { id: 1, email: 'tom@example.com', name: 'Tom Becker', handle: 'tom' },
      organisations: [{ organisationId: 7, organisationName: 'Brightline Studio', role: 'MEMBER', currency: 'EUR' }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Opens the week of 2 March with these entries. */
  async function open(entries: TimeEntry[], status: TimesheetStatus = 'DRAFT') {
    const harness = await RouterTestingHarness.create(`/time?week=${MONDAY}`);
    http.expectOne('/api/orgs/7/projects').flush(PROJECTS);
    http.expectOne((r) => r.url === WEEK_URL).flush(week(MONDAY, entries, status));
    await harness.fixture.whenStable();
    return { harness, el: harness.routeNativeElement as HTMLElement };
  }

  function cell(el: HTMLElement, label: string): HTMLInputElement {
    const input = el.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
    if (!input) {
      throw new Error(`No cell "${label}"`);
    }
    return input;
  }

  function type(input: HTMLInputElement, text: string): void {
    input.value = text;
    input.dispatchEvent(new Event('input'));
  }

  /** Types into a cell and leaves it, which is what saves it. */
  function fill(input: HTMLInputElement, text: string): void {
    type(input, text);
    input.dispatchEvent(new Event('blur'));
  }

  function press(target: HTMLElement, key: string): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  }

  function button(el: HTMLElement, label: string): HTMLButtonElement {
    const found = [...el.querySelectorAll('button')].find((b) => b.textContent.trim() === label);
    if (!found) {
      throw new Error(`No button "${label}"`);
    }
    return found;
  }

  it('saves what is typed into an empty cell and shows it without loading the week again', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const tuesday = cell(el, 'WEB, Design, Tuesday');
    expect(cell(el, 'WEB, Design, Monday').value).toBe('1h');
    expect(tuesday.value).toBe('');

    fill(tuesday, '1,5');
    const created = http.expectOne(ENTRIES_URL);
    // No billable flag and no note: the server decides whether client work is billable.
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({ projectId: 1, taskId: 5, date: '2026-03-03', minutes: 90, clientKey: expect.stringMatching(/^[0-9a-f]{32}$/) });
    created.flush(entry(2, '2026-03-03', 90));
    await harness.fixture.whenStable();

    expect(tuesday.value).toBe('1h 30m');
    expect(el.querySelector('tbody tr td:last-child')?.textContent).toContain('2h 30m');
    expect(el.querySelector('.timesheet-status')?.textContent).toContain('2h 30m total');
    expect(el.querySelectorAll('.entries li')).toHaveLength(2);
    http.verify();
  });

  it('sends one save per cell at a time, and the latest value wins without a second entry', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const tuesday = cell(el, 'WEB, Design, Tuesday');

    fill(tuesday, '1');
    const created = http.expectOne(ENTRIES_URL);
    // The save is slow, and two more values are typed before it answers.
    fill(tuesday, '2');
    fill(tuesday, '3');
    await harness.fixture.whenStable();
    http.expectNone(ENTRIES_URL);
    expect(tuesday.value).toBe('3');

    created.flush(entry(2, '2026-03-03', 60));
    const updated = http.expectOne(`${ENTRIES_URL}/2`);
    expect(updated.request.method).toBe('PUT');
    expect(updated.request.body).toMatchObject({ date: '2026-03-03', minutes: 180 });
    updated.flush(entry(2, '2026-03-03', 180));
    await harness.fixture.whenStable();

    expect(tuesday.value).toBe('3h');
    expect(el.querySelectorAll('.entries li')).toHaveLength(2);
    http.verify();
  });

  it('sends a create again with the same key when its answer never came, and gets the same entry', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const tuesday = cell(el, 'WEB, Design, Tuesday');
    const wednesday = cell(el, 'WEB, Design, Wednesday');

    fill(tuesday, '1.5');
    const lost = http.expectOne(ENTRIES_URL);
    const key = (lost.request.body as { clientKey: string }).clientKey;
    // The API stored the entry, but the browser only saw the connection drop.
    lost.error(new ProgressEvent('error'));
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('');
    expect(el.querySelector('.card [role=alert]')?.textContent).toContain('Cannot reach the server');

    // Typed again, this time as two hours: the same key, so the API answers with the entry it made before.
    fill(tuesday, '2');
    const again = http.expectOne(ENTRIES_URL);
    expect(again.request.body).toMatchObject({ date: '2026-03-03', minutes: 120, clientKey: key });
    again.flush(entry(2, '2026-03-03', 90));
    // That entry still has the time of the first try, so it is brought up to what was typed now.
    const updated = http.expectOne(`${ENTRIES_URL}/2`);
    expect(updated.request.method).toBe('PUT');
    expect(updated.request.body).toMatchObject({ minutes: 120 });
    updated.flush(entry(2, '2026-03-03', 120));
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('2h');
    expect(el.querySelectorAll('.entries li')).toHaveLength(2);

    // Another cell is another entry and has a key of its own.
    fill(wednesday, '1');
    const other = http.expectOne(ENTRIES_URL);
    expect((other.request.body as { clientKey: string }).clientKey).not.toBe(key);
    other.flush(entry(3, '2026-03-04', 60));
    await harness.fixture.whenStable();
    http.verify();
  });

  it('gives the form a key per new entry as well, the same one when the same entry is sent again', async () => {
    const { harness, el } = await open([]);
    const project = el.querySelector<HTMLSelectElement>('#te-project')!;
    project.selectedIndex = 1;
    project.dispatchEvent(new Event('change'));
    http.expectOne('/api/projects/1/tasks').flush([]);
    const duration = el.querySelector<HTMLInputElement>('#te-duration')!;
    const send = async (text: string) => {
      type(duration, text);
      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      await harness.fixture.whenStable();
      return http.expectOne(ENTRIES_URL);
    };

    const lost = await send('1h');
    const key = (lost.request.body as { clientKey: string }).clientKey;
    expect(key).toMatch(/^[0-9a-f]{32}$/);
    lost.error(new ProgressEvent('error'));
    const again = await send('1h');
    expect((again.request.body as { clientKey: string }).clientKey).toBe(key);
    again.flush(entry(4, MONDAY, 60, { taskId: undefined, taskTitle: undefined }));
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.entries li')).toHaveLength(1);

    // The same hour once more is a second entry that is meant, and gets a key of its own.
    const second = await send('1h');
    expect((second.request.body as { clientKey: string }).clientKey).not.toBe(key);
    second.flush(entry(5, MONDAY, 60, { taskId: undefined, taskTitle: undefined }));
    await harness.fixture.whenStable();
    expect(el.querySelector('.cell-several')).not.toBeNull();
    http.verify();
  });

  it('puts the saved time back and says why when the API refuses', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const monday = cell(el, 'WEB, Design, Monday');

    fill(monday, '2');
    http
      .expectOne(`${ENTRIES_URL}/1`)
      .flush(
        { status: 409, code: 'timesheet_locked', message: "The timesheet for the week of 2026-03-02 is submitted and can't be changed" },
        { status: 409, statusText: 'Conflict' },
      );
    await harness.fixture.whenStable();

    expect(monday.value).toBe('1h');
    expect(el.querySelector('.card [role=alert]')?.textContent).toContain('WEB, Design, Monday: The timesheet for the week of 2026-03-02 is submitted');
    http.verify();
  });

  it('keeps the note and the billable flag of an entry, and does not delete a note from the grid', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60, { description: 'Call with Ruth', billable: false })]);
    const monday = cell(el, 'WEB, Design, Monday, has a note');
    expect(monday.title).toBe('Call with Ruth');

    fill(monday, '0:45');
    const updated = http.expectOne(`${ENTRIES_URL}/1`);
    expect(updated.request.body).toEqual({ projectId: 1, taskId: 5, date: MONDAY, minutes: 45, description: 'Call with Ruth', billable: false });
    updated.flush(entry(1, MONDAY, 45, { description: 'Call with Ruth', billable: false }));
    await harness.fixture.whenStable();
    expect(monday.value).toBe('45m');

    fill(monday, '');
    await harness.fixture.whenStable();
    expect(monday.value).toBe('45m');
    expect(el.querySelector('.card [role=alert]')?.textContent).toContain('This entry has a note. Delete it in the list below.');
    http.verify();
  });

  it('deletes the entry of a cleared cell and keeps the emptied row', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);

    fill(cell(el, 'WEB, Design, Monday'), '');
    const deleted = http.expectOne(`${ENTRIES_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    await harness.fixture.whenStable();

    expect(cell(el, 'WEB, Design, Monday').value).toBe('');
    expect(el.querySelectorAll('.entries li.muted')).toHaveLength(1);
    expect(JSON.parse(localStorage.getItem('pp.rows.7.2026-03-02') ?? '[]')).toHaveLength(1);
    // A row without time can be taken away again.
    button(el, 'remove').click();
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.cell-input')).toHaveLength(0);
    expect(localStorage.getItem('pp.rows.7.2026-03-02')).toBeNull();
    http.verify();
  });

  it('sends nothing it can not read, and holds the week back until it is put right', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const tuesday = cell(el, 'WEB, Design, Tuesday');

    fill(tuesday, 'abc');
    await harness.fixture.whenStable();
    expect(tuesday.getAttribute('aria-invalid')).toBe('true');
    expect(tuesday.value).toBe('abc');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Tuesday: Enter time as 1:30, 1.5 or 90m');
    expect(button(el, 'Submit week').disabled).toBe(true);

    press(tuesday, 'Escape');
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('');
    expect(tuesday.getAttribute('aria-invalid')).toBeNull();
    expect(button(el, 'Submit week').disabled).toBe(false);
    http.verify();
  });

  it('does not offer typing where there are several entries or a running timer, or in a submitted week', async () => {
    const entries = [entry(1, MONDAY, 60), entry(2, MONDAY, 30), entry(3, '2026-03-03', 0, { running: true })];
    const { harness, el } = await open(entries);

    expect(el.querySelector('input[aria-label^="WEB, Design, Monday"]')).toBeNull();
    expect(el.querySelector('input[aria-label^="WEB, Design, Tuesday"]')).toBeNull();
    const several = el.querySelector<HTMLButtonElement>('.cell-several')!;
    expect(several.getAttribute('aria-label')).toBe('WEB, Design, Monday: 1h 30m in 2 entries, change them in the list below');
    several.click();
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.entries li.shown')).toHaveLength(2);
    expect(el.querySelectorAll('.cell-input')).toHaveLength(5);

    // Withdrawn weeks come back as drafts; a submitted one has nothing to type into.
    button(el, 'Submit week').click();
    http.expectOne('/api/orgs/7/timesheets/submit').flush(week(MONDAY, entries, 'SUBMITTED'));
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.cell-input, .cell-several')).toHaveLength(0);
    expect(el.querySelector('.grid-tools')).toBeNull();
    expect(el.querySelector('.timesheet-grid tbody tr td:nth-child(2)')?.textContent?.trim()).toBe('1h 30m');
    http.verify();
  });

  it('waits for a cell that is still being saved before it submits the week', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);

    fill(cell(el, 'WEB, Design, Friday'), '4');
    button(el, 'Submit week').click();
    const created = http.expectOne(ENTRIES_URL);
    http.expectNone('/api/orgs/7/timesheets/submit');

    created.flush(entry(2, '2026-03-06', 240));
    const submitted = http.expectOne('/api/orgs/7/timesheets/submit');
    expect(submitted.request.body).toEqual({ weekStart: MONDAY });
    submitted.flush(week(MONDAY, [entry(1, MONDAY, 60), entry(2, '2026-03-06', 240)], 'SUBMITTED'));
    await harness.fixture.whenStable();
    expect(el.querySelector('.timesheet-status')?.textContent).toContain('Submitted');
    http.verify();
  });

  it('moves between cells with Enter and the arrow keys', async () => {
    const admin = { projectId: 2, projectCode: 'ADM', projectName: 'Admin', taskId: undefined, taskTitle: undefined };
    const { harness, el } = await open([entry(1, MONDAY, 60, admin), entry(2, MONDAY, 60)]);
    const adminMonday = cell(el, 'ADM, Admin, Monday');

    adminMonday.focus();
    press(adminMonday, 'ArrowDown');
    expect(document.activeElement).toBe(cell(el, 'WEB, Design, Monday'));
    press(cell(el, 'WEB, Design, Monday'), 'ArrowUp');
    expect(document.activeElement).toBe(adminMonday);

    // Left and right belong to the text until the caret is at its end.
    adminMonday.setSelectionRange(0, 0);
    press(adminMonday, 'ArrowRight');
    expect(document.activeElement).toBe(adminMonday);
    adminMonday.setSelectionRange(2, 2);
    press(adminMonday, 'ArrowRight');
    expect(document.activeElement).toBe(cell(el, 'ADM, Admin, Tuesday'));

    // Enter saves and moves down.
    const adminTuesday = cell(el, 'ADM, Admin, Tuesday');
    type(adminTuesday, '90m');
    press(adminTuesday, 'Enter');
    expect(document.activeElement).toBe(cell(el, 'WEB, Design, Tuesday'));
    // The blur that the move causes must not save the cell a second time.
    adminTuesday.dispatchEvent(new Event('blur'));
    const created = http.expectOne(ENTRIES_URL);
    expect(created.request.body).toMatchObject({ projectId: 2, taskId: null, date: '2026-03-03', minutes: 90 });
    created.flush(entry(3, '2026-03-03', 90, admin));
    await harness.fixture.whenStable();
    expect(adminTuesday.value).toBe('1h 30m');
    http.verify();
  });

  it('adds a row, remembers it for the week and copies the rows of last week', async () => {
    const { harness, el } = await open([]);
    expect(el.querySelector('.timesheet-grid tbody')?.textContent).toContain('Add a row to start.');

    const project = el.querySelector<HTMLSelectElement>('#row-project')!;
    // Finished projects take no time and are not offered.
    expect([...project.options].map((o) => o.textContent.trim())).toEqual(['Project…', 'WEB · Website', 'ADM · Admin']);
    project.selectedIndex = 2;
    project.dispatchEvent(new Event('change'));
    http.expectOne('/api/projects/2/tasks').flush([]);
    await harness.fixture.whenStable();
    button(el, 'Add row').click();
    await harness.fixture.whenStable();

    expect(cell(el, 'ADM, Admin, Monday').value).toBe('');
    expect(el.querySelector('tbody tr .badge')?.textContent).toContain('non-billable');
    expect(JSON.parse(localStorage.getItem('pp.rows.7.2026-03-02') ?? '[]')).toEqual([
      { projectId: 2, projectCode: 'ADM', projectName: 'Admin', taskId: null, taskTitle: null, billable: false },
    ]);

    button(el, 'Copy rows from last week').click();
    const lastWeek = http.expectOne((r) => r.url === WEEK_URL);
    expect(lastWeek.request.params.get('date')).toBe('2026-02-23');
    lastWeek.flush(
      week('2026-02-23', [
        entry(11, '2026-02-23', 120),
        entry(12, '2026-02-24', 60, { projectId: 2, projectCode: 'ADM', projectName: 'Admin', taskId: undefined, taskTitle: undefined }),
        entry(13, '2026-02-25', 60, { projectId: 3, projectCode: 'OLD', projectName: 'Old site', taskId: undefined, taskTitle: undefined }),
      ]),
    );
    await harness.fixture.whenStable();

    // The Admin row is there already and the old site is finished: one row comes over, without its time.
    expect(el.querySelector('[role=status]')?.textContent).toContain('Added 1 row from last week.');
    expect(cell(el, 'WEB, Design, Monday').value).toBe('');
    expect(el.querySelectorAll('.timesheet-grid tbody tr')).toHaveLength(2);
    http.verify();
  });

  it('asks the API for this week, and keeps another week in the link', async () => {
    const harness = await RouterTestingHarness.create('/time');
    http.expectOne('/api/orgs/7/projects').flush(PROJECTS);
    const el = harness.routeNativeElement as HTMLElement;
    const router = TestBed.inject(Router);

    /** Answers the request for the week on screen and says which day it asked for. */
    async function answer(start: string): Promise<string | null> {
      await harness.fixture.whenStable();
      const request = http.expectOne((r) => r.url === WEEK_URL);
      request.flush(week(start));
      await harness.fixture.whenStable();
      return request.request.params.get('date');
    }

    // No date: which week "this week" is depends on the organisation's time zone, not the browser's.
    expect(await answer('2026-03-09')).toBeNull();
    expect(el.querySelector('.week-label')?.textContent).toContain('9 Mar');
    expect(el.querySelector<HTMLInputElement>('#te-date')?.value).toBe('2026-03-09');

    el.querySelector<HTMLButtonElement>('[aria-label="Previous week"]')!.click();
    expect(await answer(MONDAY)).toBe(MONDAY);
    expect(router.url).toBe(`/time?week=${MONDAY}`);
    // A new entry starts in the week on screen.
    expect(el.querySelector<HTMLInputElement>('#te-date')?.value).toBe(MONDAY);

    el.querySelector<HTMLButtonElement>('[aria-label="Next week"]')!.click();
    expect(await answer('2026-03-09')).toBeNull();
    expect(router.url).toBe('/time');

    el.querySelector<HTMLButtonElement>('[aria-label="Next week"]')!.click();
    expect(await answer('2026-03-16')).toBe('2026-03-16');
    button(el, 'This week').click();
    expect(await answer('2026-03-09')).toBeNull();
    expect(router.url).toBe('/time');
    http.verify();
  });

  it('opens the week a link names, from any day of it', async () => {
    const harness = await RouterTestingHarness.create('/time?week=2026-03-04');
    http.expectOne('/api/orgs/7/projects').flush(PROJECTS);
    const request = http.expectOne((r) => r.url === WEEK_URL);
    expect(request.request.params.get('date')).toBe(MONDAY);
    request.flush(week(MONDAY));
    await harness.fixture.whenStable();

    expect((harness.routeNativeElement as HTMLElement).querySelector('.week-label')?.textContent).toContain('2 Mar');
    http.verify();
  });
});
