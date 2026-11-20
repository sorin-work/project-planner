import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { OrgContext } from '../../core/org-context.service';
import { TimeEntry, TimesheetStatus, Week } from '../../core/models';
import { TimesheetPage } from './timesheet.page';

const WEEK_URL = '/api/orgs/7/timesheets/week';
const ENTRIES_URL = '/api/orgs/7/time-entries';
// 2026-03-02 is a Monday.
const MONDAY = '2026-03-02';

const PROJECTS = [
  { id: 1, code: 'WEB', name: 'Website', status: 'ACTIVE', clientId: 4 },
  { id: 2, code: 'ADM', name: 'Admin', status: 'ACTIVE' },
  { id: 3, code: 'OLD', name: 'Old site', status: 'DONE', clientId: 4 },
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
      organisations: [
        { organisationId: 7, organisationName: 'Brightline Studio', role: 'MEMBER', currency: 'EUR' },
        { organisationId: 8, organisationName: 'Side Project', role: 'OWNER', currency: 'USD' },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Opens the week of 2 March with these entries. */
  async function open(entries: TimeEntry[], status: TimesheetStatus = 'DRAFT') {
    const harness = await RouterTestingHarness.create(`/time?week=${MONDAY}`);
    http.expectOne('/api/orgs/7/projects/options').flush(PROJECTS);
    http.expectOne((r) => r.url === WEEK_URL).flush(week(MONDAY, entries, status));
    await harness.fixture.whenStable();
    return { harness, el: harness.routeNativeElement as HTMLElement };
  }

  it('marks the holidays of the week in the header and still takes time on them', async () => {
    const harness = await RouterTestingHarness.create(`/time?week=${MONDAY}`);
    http.expectOne('/api/orgs/7/projects/options').flush(PROJECTS);
    const withHoliday = { ...week(MONDAY, [entry(1, MONDAY, 60)]), days: [{ date: '2026-03-04', minutes: 0, holiday: 'Spring holiday' }] };
    http.expectOne((r) => r.url === WEEK_URL).flush(withHoliday);
    await harness.fixture.whenStable();
    const el = harness.routeNativeElement as HTMLElement;
    const headers = [...el.querySelectorAll('.timesheet-grid thead th')];
    const wednesday = headers.find((th) => th.textContent.includes('Wed'))!;
    expect(wednesday.textContent).toContain('Spring holiday');
    expect(wednesday.classList.contains('weekend')).toBe(true);
    expect(headers.find((th) => th.textContent.includes('Tue'))!.classList.contains('weekend')).toBe(false);
    expect(cell(el, 'WEB, Design, Wednesday').disabled).toBe(false);
  });

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

  function press(target: HTMLElement, key: string, modifiers: KeyboardEventInit = {}): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...modifiers }));
  }

  /** Answers the request that reads the week again, which follows every save that failed. */
  function answerReload(entries: TimeEntry[], status: TimesheetStatus = 'DRAFT'): void {
    http.expectOne((r) => r.url === WEEK_URL).flush(week(MONDAY, entries, status));
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
    expect(cell(el, 'WEB, Design, Monday').value).toBe('1:00');
    expect(tuesday.value).toBe('');

    fill(tuesday, '1,5');
    const created = http.expectOne(ENTRIES_URL);
    // No billable flag and no note: the server decides whether client work is billable.
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({ projectId: 1, taskId: 5, date: '2026-03-03', minutes: 90, clientKey: expect.stringMatching(/^[0-9a-f]{32}$/) });
    created.flush(entry(2, '2026-03-03', 90));
    await harness.fixture.whenStable();

    expect(tuesday.value).toBe('1:30');
    expect(el.querySelector('tbody tr td:last-child')?.textContent).toContain('2:30');
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
    // What was read is in the cell at once, as hours and minutes.
    expect(tuesday.value).toBe('3:00');

    created.flush(entry(2, '2026-03-03', 60));
    const updated = http.expectOne(`${ENTRIES_URL}/2`);
    expect(updated.request.method).toBe('PUT');
    expect(updated.request.body).toMatchObject({ date: '2026-03-03', minutes: 180 });
    updated.flush(entry(2, '2026-03-03', 180));
    await harness.fixture.whenStable();

    expect(tuesday.value).toBe('3:00');
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
    // The week is read again, and in this case too early to see the entry.
    answerReload([entry(1, MONDAY, 60)]);
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('');
    expect(el.querySelector('.card [role=alert]')?.textContent).toContain('WEB, Design, Tuesday: Cannot reach the server');

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
    expect(tuesday.value).toBe('2:00');
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

    expect(monday.value).toBe('1:00');
    expect(monday.getAttribute('aria-invalid')).toBe('true');
    const reason = 'WEB, Design, Monday: The timesheet for the week of 2026-03-02 is submitted';
    expect(el.querySelector('#grid-problems')?.textContent).toContain(reason);

    // The week is read again after a save that failed. It was submitted in another tab: nothing
    // can be typed any more, and the reason is still there to read.
    answerReload([entry(1, MONDAY, 60)], 'SUBMITTED');
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.cell-input')).toHaveLength(0);
    expect(el.querySelector('.timesheet-grid tbody tr td:nth-child(2)')?.textContent?.trim()).toBe('1:00');
    expect(el.querySelector('#grid-problems')?.textContent).toContain(reason);
    http.verify();
  });

  it('reads the week again when an entry turns out to be gone', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60), entry(2, '2026-03-03', 30)]);

    fill(cell(el, 'WEB, Design, Monday'), '2');
    http.expectOne(`${ENTRIES_URL}/1`).flush({ status: 404, code: 'not_found', message: 'Time entry not found' }, { status: 404, statusText: 'Not Found' });
    // Deleted in another tab. The old hour must not stay on screen as if it were still there.
    answerReload([entry(2, '2026-03-03', 30)]);
    await harness.fixture.whenStable();

    expect(cell(el, 'WEB, Design, Monday').value).toBe('');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Monday: Time entry not found');
    expect(el.querySelector('.timesheet-status')?.textContent).toContain('30m total');
    http.verify();
  });

  it('reads the week again when the tab is looked at again', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    expect(cell(el, 'WEB, Design, Tuesday').value).toBe('');

    document.dispatchEvent(new Event('visibilitychange'));
    // Tuesday was filled in from another tab. Typing into a cell that only looks empty would make a second entry.
    answerReload([entry(1, MONDAY, 60), entry(2, '2026-03-03', 90)]);
    await harness.fixture.whenStable();
    expect(cell(el, 'WEB, Design, Tuesday').value).toBe('1:30');

    fill(cell(el, 'WEB, Design, Tuesday'), '2');
    const updated = http.expectOne(`${ENTRIES_URL}/2`);
    expect(updated.request.method).toBe('PUT');
    updated.flush(entry(2, '2026-03-03', 120));
    await harness.fixture.whenStable();
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
    expect(monday.value).toBe('0:45');

    fill(monday, '');
    await harness.fixture.whenStable();
    expect(monday.value).toBe('0:45');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Monday: This entry has a note. Delete it in the list below.');
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
    expect(JSON.parse(localStorage.getItem('pp.user.1.rows.7.2026-03-02') ?? '[]')).toHaveLength(1);
    // A row without time can be taken away again.
    button(el, 'remove').click();
    await harness.fixture.whenStable();
    expect(el.querySelectorAll('.cell-input')).toHaveLength(0);
    expect(localStorage.getItem('pp.user.1.rows.7.2026-03-02')).toBeNull();
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

    // Decimal hours are not rounded to minutes nobody typed.
    fill(tuesday, '1.555');
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('1.555');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Tuesday: That is not a whole number of minutes');
    http.verify();
  });

  it('shows what it read as hours and minutes at once, before the save has answered', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const tuesday = cell(el, 'WEB, Design, Tuesday');

    // An hour and eighteen minutes. Whoever meant half past one sees it before leaving the row.
    fill(tuesday, '1.30');
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('1:18');
    const created = http.expectOne(ENTRIES_URL);
    expect(created.request.body).toMatchObject({ minutes: 78 });
    created.flush(entry(2, '2026-03-03', 78));
    await harness.fixture.whenStable();
    expect(tuesday.value).toBe('1:18');
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
    expect(el.querySelector('.timesheet-grid tbody tr td:nth-child(2)')?.textContent?.trim()).toBe('1:30');
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

  it('does not submit the week when a cell that was still being saved is refused', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const friday = cell(el, 'WEB, Design, Friday');

    fill(friday, '4');
    button(el, 'Submit week').click();
    http
      .expectOne(ENTRIES_URL)
      .flush({ status: 400, code: 'project_done', message: 'Website is finished; reopen it to log more time' }, { status: 400, statusText: 'Bad Request' });
    // The week would have gone in without Friday. It stays open, and says why.
    http.expectNone('/api/orgs/7/timesheets/submit');
    answerReload([entry(1, MONDAY, 60)]);
    await harness.fixture.whenStable();

    expect(el.querySelector('.timesheet-status')?.textContent).toContain('Draft');
    expect(friday.value).toBe('');
    expect(friday.getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Friday: Website is finished; reopen it to log more time');
    expect(el.textContent).toContain('The week was not submitted, because not everything in it could be saved.');

    // Having read that, the week can be submitted as it is. What was not saved is still listed afterwards.
    button(el, 'Submit week').click();
    http.expectOne('/api/orgs/7/timesheets/submit').flush(week(MONDAY, [entry(1, MONDAY, 60)], 'SUBMITTED'));
    await harness.fixture.whenStable();
    expect(el.querySelector('.timesheet-status')?.textContent).toContain('Submitted');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('WEB, Design, Friday: Website is finished');
    expect(el.textContent).not.toContain('The week was not submitted');
    http.verify();
  });

  it('does not submit either when the change was refused before anything was sent', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60, { description: 'Call with Ruth' })]);

    // Clearing an entry with a note is not done from the grid, so this is a change that was not saved.
    // It is refused in the same moment in which the click on the button leaves the cell.
    fill(cell(el, 'WEB, Design, Monday, has a note'), '');
    button(el, 'Submit week').click();
    await harness.fixture.whenStable();
    http.expectNone('/api/orgs/7/timesheets/submit');
    expect(el.querySelector('#grid-problems')?.textContent).toContain('This entry has a note');
    expect(el.textContent).toContain('The week was not submitted');

    // Now that it has been said, pressing again submits the week as it stands.
    button(el, 'Submit week').click();
    http.expectOne('/api/orgs/7/timesheets/submit').flush(week(MONDAY, [entry(1, MONDAY, 60, { description: 'Call with Ruth' })], 'SUBMITTED'));
    await harness.fixture.whenStable();
    http.verify();
  });

  it('submits without being asked twice once a refused cell has been put right', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const friday = cell(el, 'WEB, Design, Friday');

    fill(friday, '4');
    http.expectOne(ENTRIES_URL).flush({ status: 500, code: 'internal_error', message: 'Something went wrong on our side' }, { status: 500, statusText: 'Server Error' });
    answerReload([entry(1, MONDAY, 60)]);
    await harness.fixture.whenStable();
    expect(friday.getAttribute('aria-invalid')).toBe('true');

    fill(friday, '4');
    http.expectOne(ENTRIES_URL).flush(entry(2, '2026-03-06', 240));
    await harness.fixture.whenStable();
    expect(friday.getAttribute('aria-invalid')).toBeNull();
    expect(el.querySelector('#grid-problems')).toBeNull();

    button(el, 'Submit week').click();
    http.expectOne('/api/orgs/7/timesheets/submit').flush(week(MONDAY, [entry(1, MONDAY, 60), entry(2, '2026-03-06', 240)], 'SUBMITTED'));
    await harness.fixture.whenStable();
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

    // With a modifier the keys are not the grid's: Shift+Down selects, Alt+Left goes back.
    press(adminMonday, 'ArrowDown', { shiftKey: true });
    press(adminMonday, 'ArrowDown', { altKey: true });
    press(adminMonday, 'ArrowDown', { ctrlKey: true });
    press(adminMonday, 'ArrowDown', { metaKey: true });
    expect(document.activeElement).toBe(adminMonday);
    const emptyTuesday = cell(el, 'ADM, Admin, Tuesday');
    emptyTuesday.focus();
    press(emptyTuesday, 'ArrowLeft', { altKey: true });
    expect(document.activeElement).toBe(emptyTuesday);
    adminMonday.focus();

    // Left and right belong to the text until the caret is at its end.
    adminMonday.setSelectionRange(0, 0);
    press(adminMonday, 'ArrowRight');
    expect(document.activeElement).toBe(adminMonday);
    adminMonday.setSelectionRange(adminMonday.value.length, adminMonday.value.length);
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
    expect(adminTuesday.value).toBe('1:30');
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
    expect(JSON.parse(localStorage.getItem('pp.user.1.rows.7.2026-03-02') ?? '[]')).toEqual([
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

  it('starts over in another organisation, and shows no week when the new one can not be loaded', async () => {
    const { harness, el } = await open([entry(1, MONDAY, 60)]);
    const project = el.querySelector<HTMLSelectElement>('#te-project')!;
    project.selectedIndex = 1;
    project.dispatchEvent(new Event('change'));
    http.expectOne('/api/projects/1/tasks').flush([{ id: 5, projectId: 1, title: 'Design', milestone: false }]);
    await harness.fixture.whenStable();
    fill(cell(el, 'WEB, Design, Tuesday'), 'abc');
    await harness.fixture.whenStable();

    // The switcher in the side bar changes the organisation without leaving the page.
    TestBed.inject(OrgContext).select(8);
    await harness.fixture.whenStable();
    const projects = http.expectOne('/api/orgs/8/projects/options');
    const week8 = http.expectOne((r) => r.url === '/api/orgs/8/timesheets/week');
    // The week of the first organisation is off the screen at once, with what was typed into it.
    expect(el.querySelector('.timesheet-grid')).toBeNull();
    projects.flush([{ id: 21, code: 'SIDE', name: 'Side', status: 'ACTIVE' }]);
    week8.flush(week(MONDAY, []));
    await harness.fixture.whenStable();

    // Its projects, the task list and the choice in the form went with it.
    const options = (id: string) => [...el.querySelector<HTMLSelectElement>(id)!.options].map((o) => o.textContent.trim());
    expect(options('#te-project')).toEqual(['Choose…', 'SIDE · Side']);
    expect(options('#te-task')).toEqual(['No task']);
    expect(el.querySelector<HTMLSelectElement>('#te-project')!.selectedIndex).toBe(0);
    expect(el.querySelector('#grid-problems')).toBeNull();
    expect(el.querySelectorAll('.cell-input')).toHaveLength(0);

    // Another week that can not be loaded does not leave this one on screen under its name.
    el.querySelector<HTMLButtonElement>('[aria-label="Previous week"]')!.click();
    await harness.fixture.whenStable();
    http
      .expectOne((r) => r.url === '/api/orgs/8/timesheets/week')
      .flush({ status: 500, code: 'internal_error', message: 'Something went wrong on our side' }, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Something went wrong on our side');
    expect(el.querySelector('.timesheet-status')).toBeNull();
    http.verify();
  });

  it('asks the API for this week, and keeps another week in the link', async () => {
    const harness = await RouterTestingHarness.create('/time');
    http.expectOne('/api/orgs/7/projects/options').flush(PROJECTS);
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
    http.expectOne('/api/orgs/7/projects/options').flush(PROJECTS);
    const request = http.expectOne((r) => r.url === WEEK_URL);
    expect(request.request.params.get('date')).toBe(MONDAY);
    request.flush(week(MONDAY));
    await harness.fixture.whenStable();

    expect((harness.routeNativeElement as HTMLElement).querySelector('.week-label')?.textContent).toContain('2 Mar');
    http.verify();
  });
});
