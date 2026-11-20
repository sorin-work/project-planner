import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { Role, TimeReport, UtilisationReport } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ReportsPage } from './reports.page';

const TIME_URL = '/api/orgs/7/reports/time';
const UTILISATION_URL = '/api/orgs/7/reports/utilisation';

const BY_PROJECT: TimeReport = {
  from: '2026-03-01',
  to: '2026-03-31',
  groupBy: 'PROJECT',
  rows: [
    { key: '12', label: 'WEB · Website', minutes: 150, billableMinutes: 150, billableAmount: 219.99 },
    { key: '13', label: 'INT · Internal', minutes: 90, billableMinutes: 30, billableAmount: 50 },
  ],
  total: { key: 'total', label: 'Total', minutes: 240, billableMinutes: 180, billableAmount: 269.99 },
};

const BY_TASK: TimeReport = {
  from: '2026-03-01',
  to: '2026-03-31',
  groupBy: 'TASK',
  rows: [{ key: '77', label: 'WEB · Website · Design', minutes: 150, billableMinutes: 150, billableAmount: 219.99 }],
  total: { key: 'total', label: 'Total', minutes: 150, billableMinutes: 150, billableAmount: 219.99 },
};

const UTILISATION: UtilisationReport = {
  from: '2026-03-09',
  to: '2026-03-15',
  countedTo: '2026-03-11',
  workingDays: 3,
  rows: [
    { userId: 1, name: 'Gwen', minutes: 300, billableMinutes: 300, capacityMinutes: 1440, utilisationPct: 20.8 },
    { userId: 2, name: 'Hugo', minutes: 408, billableMinutes: 288, capacityMinutes: 1152, utilisationPct: 25 },
  ],
  total: { userId: null, name: 'Total', minutes: 708, billableMinutes: 588, capacityMinutes: 2592, utilisationPct: 22.7 },
};

describe('ReportsPage', () => {
  let http: HttpTestingController;

  function signIn(role: Role): void {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    TestBed.inject(AuthService).me.set({
      user: { id: 1, email: 'gwen@example.com', name: 'Gwen', handle: 'gwen' },
      organisations: [
        { organisationId: 7, organisationName: 'Brightline Studio', role, currency: 'EUR' },
        { organisationId: 8, organisationName: 'Side Project', role: 'OWNER', currency: 'USD' },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  async function open(role: Role = 'OWNER') {
    signIn(role);
    const fixture = TestBed.createComponent(ReportsPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    /** The request for the report on screen, which the test then answers. */
    const asked = async (url: string): Promise<TestRequest> => {
      await fixture.whenStable();
      return http.expectOne((r) => r.url === url);
    };
    return { fixture, el, asked };
  }

  function button(el: HTMLElement, label: string): HTMLButtonElement {
    const found = [...el.querySelectorAll('button')].find((b) => b.textContent.trim() === label);
    if (!found) {
      throw new Error(`No button "${label}"`);
    }
    return found;
  }

  function texts(el: HTMLElement, selector: string): string[] {
    return [...el.querySelectorAll(selector)].map((cell) => cell.textContent.trim());
  }

  afterEach(() => http.verify());

  it('shows this month per project, to the cent, and the tasks of a project on a click', async () => {
    const { fixture, el, asked } = await open();
    const first = await asked(TIME_URL);
    expect(first.request.params.toString()).toBe('preset=this_month&groupBy=project');
    first.flush(BY_PROJECT);
    await fixture.whenStable();

    expect(texts(el, '.report tbody tr td:first-child')).toEqual(['WEB · Website', 'INT · Internal']);
    expect(texts(el, '.report tbody tr:first-child td.num')).toEqual(['2h 30m', '2h 30m', '€219.99']);
    // The footer is what the rows add up to: 219.99 and 50.00.
    expect(texts(el, '.report tfoot th.num')).toEqual(['4h', '3h', '€269.99']);
    // The dates the preset came to are in the inputs.
    expect(el.querySelector<HTMLInputElement>('#rep-from')?.value).toBe('2026-03-01');
    expect(el.querySelector<HTMLInputElement>('#rep-to')?.value).toBe('2026-03-31');

    button(el, 'WEB · Website').click();
    const tasks = await asked(TIME_URL);
    expect(tasks.request.params.toString()).toBe('preset=this_month&groupBy=task&projectId=12');
    tasks.flush(BY_TASK);
    await fixture.whenStable();
    expect(el.querySelector('.report-drill')?.textContent).toContain('Tasks in WEB · Website');
    expect(texts(el, '.report tbody tr td:first-child')).toEqual(['WEB · Website · Design']);

    button(el, 'All projects').click();
    const back = await asked(TIME_URL);
    expect(back.request.params.toString()).toBe('preset=this_month&groupBy=project');
    back.flush(BY_PROJECT);
  });

  it('takes another preset, dates of one\'s own and another grouping', async () => {
    const { fixture, el, asked } = await open();
    (await asked(TIME_URL)).flush(BY_PROJECT);
    await fixture.whenStable();

    button(el, 'Last week').click();
    const lastWeek = await asked(TIME_URL);
    expect(lastWeek.request.params.toString()).toBe('preset=last_week&groupBy=project');
    lastWeek.flush({ ...BY_PROJECT, from: '2026-03-02', to: '2026-03-08' });
    await fixture.whenStable();
    expect(el.querySelector('.report-tools .active')?.textContent).toContain('Last week');

    const from = el.querySelector<HTMLInputElement>('#rep-from')!;
    expect(from.value).toBe('2026-03-02');
    from.value = '2026-03-04';
    from.dispatchEvent(new Event('input'));
    from.dispatchEvent(new Event('change'));
    const custom = await asked(TIME_URL);
    expect(custom.request.params.toString()).toBe('from=2026-03-04&to=2026-03-08&groupBy=project');
    custom.flush({ ...BY_PROJECT, from: '2026-03-04', to: '2026-03-08' });
    await fixture.whenStable();
    expect(el.querySelector('.report-tools .segmented .active')).toBeNull();

    const group = el.querySelector<HTMLSelectElement>('#rep-group')!;
    group.selectedIndex = 2;
    group.dispatchEvent(new Event('change'));
    const byMember = await asked(TIME_URL);
    expect(byMember.request.params.toString()).toBe('from=2026-03-04&to=2026-03-08&groupBy=member');
    byMember.flush({ ...BY_PROJECT, groupBy: 'MEMBER', rows: [] });
    await fixture.whenStable();
    expect(el.querySelector('.report tbody')?.textContent).toContain('No time logged from');
  });

  it('shows utilisation against capacity and says what that is', async () => {
    const { fixture, el, asked } = await open('MANAGER');
    (await asked(TIME_URL)).flush(BY_PROJECT);

    button(el, 'Utilisation').click();
    const request = await asked(UTILISATION_URL);
    expect(request.request.params.toString()).toBe('preset=this_month');
    request.flush(UTILISATION);
    await fixture.whenStable();

    expect(texts(el, '.report tbody tr:first-child td')).toEqual(['Gwen', '5h', '5h', '24h', '', '20.8%']);
    expect(texts(el, '.report tfoot th, .report tfoot td')).toEqual(['Total', '11h 48m', '9h 48m', '43h 12m', '', '22.7%']);
    const note = el.querySelector('section.card > p.muted')?.textContent ?? '';
    expect(note).toContain('for the 3 working days from 9 Mar');
    expect(note).toContain('Days after today are not counted');
    expect(note).toContain('People who have left the organisation are not listed');
    expect(note).toContain('holidays or time off');
  });

  it('gives members their own time without amounts and without utilisation', async () => {
    const { fixture, el, asked } = await open('MEMBER');
    (await asked(TIME_URL)).flush({
      ...BY_PROJECT,
      rows: BY_PROJECT.rows.map((row) => ({ ...row, billableAmount: null })),
      total: { ...BY_PROJECT.total, billableAmount: null },
    });
    await fixture.whenStable();

    expect(texts(el, '.page-header .segmented button')).toEqual(['Summary', 'Export']);
    expect(texts(el, '.report thead th')).toEqual(['Project', 'Share of the longest row', 'Time', 'Billable']);
    expect(texts(el, '.report tfoot th.num')).toEqual(['4h', '3h']);
    expect(el.textContent).toContain('Your own time');
  });

  it('starts over in another organisation, and takes the rows away when a report can not be loaded', async () => {
    const { fixture, el, asked } = await open();
    (await asked(TIME_URL)).flush(BY_PROJECT);
    await fixture.whenStable();
    button(el, 'WEB · Website').click();
    (await asked(TIME_URL)).flush(BY_TASK);
    await fixture.whenStable();
    expect(el.querySelector('.report-drill')).not.toBeNull();

    // The switcher in the side bar changes the organisation without leaving the page.
    TestBed.inject(OrgContext).select(8);
    const other = await asked('/api/orgs/8/reports/time');
    // The project that was open belongs to the first organisation and is not asked for here.
    expect(other.request.params.toString()).toBe('preset=this_month&groupBy=project');
    expect(el.querySelector('.report-drill')).toBeNull();
    expect(el.querySelector('.report')).toBeNull();

    other.flush({ status: 500, code: 'internal_error', message: 'Something went wrong on our side' }, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Something went wrong on our side');
    // Nothing of the first organisation is left under the name and the currency of the second.
    expect(el.querySelector('.report')).toBeNull();
    expect(el.textContent).not.toContain('WEB · Website');

    // And a report that fails takes the rows it was meant to replace with it.
    button(el, 'Last week').click();
    (await asked('/api/orgs/8/reports/time')).flush(BY_PROJECT);
    await fixture.whenStable();
    expect(texts(el, '.report tfoot th.num')).toEqual(['4h', '3h', 'US$269.99']);
    button(el, 'This week').click();
    (await asked('/api/orgs/8/reports/time')).flush({ status: 400, code: 'invalid_range', message: 'No such range' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(el.querySelector('.report')).toBeNull();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('No such range');
  });

  it('downloads the rows on screen as CSV, with the same range and grouping', async () => {
    // jsdom has no object URLs. The download only needs them to exist.
    const createObjectURL = vi.fn(() => 'blob:report');
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    const saved: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push(this.download);
    });
    const { fixture, el, asked } = await open();
    (await asked(TIME_URL)).flush(BY_PROJECT);
    await fixture.whenStable();

    button(el, 'Download CSV').click();
    const csv = await asked('/api/orgs/7/reports/time.csv');
    expect(csv.request.params.toString()).toBe('preset=this_month&groupBy=project');
    expect(csv.request.responseType).toBe('blob');
    csv.flush(new Blob(['from,to\r\n']));
    await fixture.whenStable();
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(saved).toEqual(['time-by-project-2026-03-01-to-2026-03-31.csv']);
    click.mockRestore();
  });
});
