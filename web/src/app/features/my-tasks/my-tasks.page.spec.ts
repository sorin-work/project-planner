import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { OrgContext } from '../../core/org-context.service';
import { Member, Role, SearchHit, SearchResponse } from '../../core/models';
import { MyTasksPage } from './my-tasks.page';

const SEARCH_URL = '/api/orgs/7/search';

function hit(id: number, title: string, dueDate?: string, status: SearchHit['status'] = 'TODO'): SearchHit {
  return { type: 'TASK', id, projectId: 3, projectCode: 'WEB', taskId: id, title, dueDate, status, priority: 'MEDIUM' };
}

function answer(tasks: SearchHit[], more = false): SearchResponse {
  return { query: '', projects: [], tasks, comments: [], more: more ? ['TASK'] : [], today: '2026-11-25' };
}

function member(userId: number, name: string, role: Role = 'MEMBER'): Member {
  return { id: userId * 10, userId, name, email: `${name}@example.com`, handle: name, role, weeklyCapacityHours: 40, joinedAt: '2026-01-01T00:00:00Z' };
}

describe('MyTasksPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 'my-tasks', component: MyTasksPage }])],
    });
    TestBed.inject(AuthService).me.set({
      user: { id: 5, email: 'maya@example.com', name: 'Maya Collins', handle: 'maya' },
      organisations: [
        { organisationId: 7, organisationName: 'Brightline Studio', role: 'MANAGER', currency: 'EUR' },
        { organisationId: 8, organisationName: 'Side Project', role: 'OWNER', currency: 'EUR' },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(tasks: SearchHit[], more = false) {
    const harness = await RouterTestingHarness.create('/my-tasks');
    http.expectOne('/api/orgs/7/members').flush([member(5, 'Maya'), member(6, 'Tom'), member(9, 'Client', 'CLIENT')]);
    const request = http.expectOne((r) => r.url === SEARCH_URL);
    expect(request.request.params.get('assigneeId')).toBe('5');
    expect(request.request.params.getAll('status')).toEqual(['TODO', 'IN_PROGRESS', 'IN_REVIEW']);
    expect(request.request.params.get('limit')).toBe('200');
    request.flush(answer(tasks, more));
    await harness.fixture.whenStable();
    return { harness, el: harness.routeNativeElement as HTMLElement };
  }

  it('groups the open tasks by the organisation date and links each to its project', async () => {
    const { el } = await open([hit(1, 'Fix login', '2026-11-20'), hit(2, 'Ship it', '2026-11-27'), hit(3, 'Someday')], true);
    expect([...el.querySelectorAll('h2')].map((h) => h.childNodes[0].textContent!.trim())).toEqual(['Overdue', 'This week', 'No date']);
    const link = el.querySelector<HTMLAnchorElement>('a[href*="task=1"]')!;
    expect(link.getAttribute('href')).toBe('/projects/3/list?task=1');
    expect(el.textContent).toContain('Only the first 200 are shown');
    // Client viewers are not in the list of people.
    expect([...el.querySelectorAll('#assignee option')].map((o) => o.textContent!.trim())).toEqual(['Me', 'Tom']);
  });

  it('ticks a task done without leaving the page, and back to what it was', async () => {
    const { harness, el } = await open([hit(1, 'Review copy', '2026-11-26', 'IN_REVIEW')]);
    el.querySelector<HTMLInputElement>('input[aria-label="Done: Review copy"]')!.click();
    const done = http.expectOne('/api/tasks/1');
    expect(done.request.method).toBe('PATCH');
    expect(done.request.body).toEqual({ status: 'DONE' });
    done.flush({});
    await harness.fixture.whenStable();
    expect(el.querySelector('h2')!.textContent).toContain('Done');

    el.querySelector<HTMLInputElement>('input[aria-label="Not done: Review copy"]')!.click();
    const undone = http.expectOne('/api/tasks/1');
    expect(undone.request.body).toEqual({ status: 'IN_REVIEW' });
    undone.flush({});
    await harness.fixture.whenStable();
    expect(el.querySelector('h2')!.textContent).toContain('This week');
  });

  it('shows somebody else when picked, and done tasks when asked', async () => {
    const { harness, el } = await open([]);
    expect(el.textContent).toContain('Nothing open');
    const select = el.querySelector<HTMLSelectElement>('#assignee')!;
    select.value = '6';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    const tom = http.expectOne((r) => r.url === SEARCH_URL);
    expect(tom.request.params.get('assigneeId')).toBe('6');
    tom.flush(answer([hit(4, 'Tom task')]));
    await harness.fixture.whenStable();
    expect(el.querySelector('h1')!.textContent).toContain('Tasks of Tom');

    el.querySelector<HTMLInputElement>('.page-header input[type=checkbox]')!.click();
    await harness.fixture.whenStable();
    const all = http.expectOne((r) => r.url === SEARCH_URL);
    expect(all.request.params.has('status')).toBe(false);
    all.flush(answer([hit(4, 'Tom task'), hit(5, 'Old one', '2026-01-01', 'DONE')]));
    await harness.fixture.whenStable();
    expect([...el.querySelectorAll('h2')].map((h) => h.childNodes[0].textContent!.trim())).toEqual(['No date', 'Done']);
  });

  it('shows only the answer to the last question, however late the earlier one comes', async () => {
    const { harness, el } = await open([]);
    const select = el.querySelector<HTMLSelectElement>('#assignee')!;
    select.value = '6';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    const tom = http.expectOne((r) => r.url === SEARCH_URL && r.params.get('assigneeId') === '6');

    select.value = '5';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    const mine = http.expectOne((r) => r.url === SEARCH_URL && r.params.get('assigneeId') === '5');
    expect(tom.cancelled).toBe(true);
    mine.flush(answer([hit(1, 'Mine')]));
    await harness.fixture.whenStable();
    expect(el.querySelector('h1')!.textContent).toContain('My tasks');
    expect(el.textContent).toContain('Mine');
    expect(el.textContent).not.toContain('Tom task');
  });

  it('forgets the person picked when the organisation changes', async () => {
    const { harness, el } = await open([]);
    const select = el.querySelector<HTMLSelectElement>('#assignee')!;
    select.value = '6';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    http.expectOne((r) => r.url === SEARCH_URL).flush(answer([hit(4, 'Tom task')]));
    await harness.fixture.whenStable();

    TestBed.inject(OrgContext).select(8);
    await harness.fixture.whenStable();
    http.expectOne('/api/orgs/8/members').flush([member(5, 'Maya', 'OWNER')]);
    const other = http.expectOne((r) => r.url === '/api/orgs/8/search');
    expect(other.request.params.get('assigneeId')).toBe('5');
    other.flush({ ...answer([]), today: '2026-11-25' });
    await harness.fixture.whenStable();
    expect(el.textContent).not.toContain('Tom task');
    expect(el.querySelector('h1')!.textContent).toContain('My tasks');
  });
});
