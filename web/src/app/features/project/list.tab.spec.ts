import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { Task } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ListTab } from './list.tab';
import { ProjectStore } from './project.store';

function task(id: number, title: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '2026-02-20T09:00:00Z', updatedAt: '2026-02-20T09:00:00Z', predecessors: [], subtaskCount: 0, loggedHours: 0,
    ...extra,
  };
}

describe('ListTab filters', () => {
  let harness: RouterTestingHarness;
  let router: Router;

  const el = () => harness.routeNativeElement as HTMLElement;
  const titles = () => [...el().querySelectorAll('.task-title')].map((b) => b.textContent?.trim());

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'list', component: ListTab }]),
        ProjectStore,
        { provide: OrgContext, useValue: { isTeam: () => true } },
        { provide: AuthService, useValue: { user: () => ({ id: 7 }) } },
      ],
    });
    TestBed.inject(ProjectStore).tasks.set([
      task(1, 'Website', { assigneeId: 7, assigneeName: 'Tom' }),
      task(2, 'Contact form', { parentId: 1, status: 'DONE', assigneeId: 3, assigneeName: 'Lena' }),
      task(3, 'Print the form', { priority: 'HIGH' }),
      task(4, 'Newsletter', { status: 'DONE' }),
    ]);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create();
  });

  it('shows everything without a filter', async () => {
    await harness.navigateByUrl('/list');
    expect(titles()).toEqual(['Website', 'Contact form', 'Print the form', 'Newsletter']);
    expect(el().querySelector('[role="status"]')?.textContent?.trim()).toBe('4 tasks');
  });

  it('takes the filter from the address, as after a reload or the back button', async () => {
    await harness.navigateByUrl('/list?q=form&status=DONE');

    // The parent stays for its subtask, greyed out, and does not count.
    expect(titles()).toEqual(['Website', 'Contact form']);
    expect(el().querySelector('tr.context-row .task-title')?.textContent?.trim()).toBe('Website');
    expect(el().querySelector('[role="status"]')?.textContent?.trim()).toBe('1 of 4 tasks');
    expect((el().querySelector('input[type="search"]') as HTMLInputElement).value).toBe('form');
  });

  it('writes a change into the address without losing the open task or adding a step to the history', async () => {
    await harness.navigateByUrl('/list?task=3');
    const navigate = vi.spyOn(router, 'navigate');

    const text = el().querySelector('input[type="search"]') as HTMLInputElement;
    text.value = 'form';
    text.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();

    expect(navigate.mock.calls[0][1]).toMatchObject({ queryParamsHandling: 'merge', replaceUrl: true });
    expect(router.url).toBe('/list?task=3&q=form');
    expect(titles()).toEqual(['Website', 'Contact form', 'Print the form']);

    const priority = el().querySelector('select[aria-label="Filter by priority"]') as HTMLSelectElement;
    priority.value = 'HIGH';
    priority.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(router.url).toBe('/list?task=3&q=form&priority=HIGH');
    expect(titles()).toEqual(['Print the form']);
  });

  it('clears the filter and leaves the rest of the address alone', async () => {
    await harness.navigateByUrl('/list?task=3&q=zzz&mine=1');
    expect(titles()).toEqual([]);
    expect(el().textContent).toContain('No task matches the filter.');

    const clear = [...el().querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Clear') as HTMLButtonElement;
    clear.click();
    await harness.fixture.whenStable();
    expect(router.url).toBe('/list?task=3');
    expect(titles()).toHaveLength(4);
  });
});
