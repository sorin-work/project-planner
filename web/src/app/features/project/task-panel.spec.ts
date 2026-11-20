import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Project, ScheduleIssue, Task } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate } from '../../shared/dates';
import { ProjectStore } from './project.store';
import { TaskPanel } from './task-panel';

function task(id: number, title: string, startDate: string, dueDate: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 7, title, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '2026-02-20T09:00:00Z', updatedAt: '2026-02-20T09:00:00Z', predecessors: [], subtaskCount: 0, loggedHours: 0,
    startDate, dueDate, ...extra,
  };
}

const design = task(1, 'Design', '2026-03-02', '2026-03-06');
const build = task(2, 'Build', '2026-03-04', '2026-03-06', {
  predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }],
});
const early: ScheduleIssue = {
  type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-11',
};
const late: ScheduleIssue = { type: 'AFTER_PROJECT_END', taskId: 2, days: 1 };

describe('TaskPanel schedule issues', () => {
  let http: HttpTestingController;

  async function render(isTeam: boolean) {
    TestBed.configureTestingModule({
      imports: [TaskPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ProjectStore,
        { provide: OrgContext, useValue: { isTeam: () => isTeam, canManage: () => false, id: () => 3 } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(ProjectStore);
    store.project.set({ id: 7, organisationId: 3 } as Project);
    store.tasks.set([design, build]);
    store.issues.set([early, late]);
    const fixture = TestBed.createComponent(TaskPanel);
    fixture.componentRef.setInput('taskId', 2);
    await fixture.whenStable();
    http.expectOne('/api/tasks/2/comments').flush([]);
    http.expectOne('/api/tasks/2/attachments').flush([]);
    await fixture.whenStable();
    return { fixture, store, el: fixture.nativeElement as HTMLElement };
  }

  function moveButton(el: HTMLElement): HTMLButtonElement | undefined {
    return [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Move to'));
  }

  afterEach(() => http.verify());

  it('shows each issue where it belongs', async () => {
    const { el } = await render(true);
    expect(el.querySelector('.deps li')?.textContent).toContain('Starts 3 working days too early for this');
    expect(el.querySelector('.panel-fields + .issue-note')?.textContent).toContain("Due 1 working day after the project's end date");
    expect(moveButton(el)?.textContent).toContain(`Move to ${formatDate('2026-03-09')}`);
  });

  it('moves the task to exactly the two suggested dates', async () => {
    const { fixture, store, el } = await render(true);
    moveButton(el)!.click();
    const patch = http.expectOne('/api/tasks/2');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ startDate: '2026-03-09', dueDate: '2026-03-11' });

    patch.flush({ task: { ...build, startDate: '2026-03-09', dueDate: '2026-03-11' }, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush({ durationDays: 0, criticalTaskIds: [], tasks: [] });
    http.expectOne('/api/projects/7/schedule-issues').flush([late]);
    await fixture.whenStable();
    expect(store.issues()).toEqual([late]);
    expect(moveButton(el)).toBeUndefined();
    expect(el.querySelector('.deps .issue-note')).toBeNull();
  });

  it('says next to the button what the move would cost, and why warnings are missing when they are', async () => {
    const { fixture, store, el } = await render(true);
    expect(el.querySelector('.issue-fix .issue-note')).toBeNull();

    store.issues.set([{ ...early, suggestionCauses: ['AFTER_PROJECT_END'] }]);
    await fixture.whenStable();
    expect(el.querySelector('.issue-fix')?.textContent).toContain("That puts it after the project's end date.");

    store.issues.set([]);
    store.scheduleError.set('Cannot reach the server. Check your connection and try again.');
    await fixture.whenStable();
    expect(moveButton(el)).toBeUndefined();
    expect(el.textContent).toContain('The schedule could not be checked after the last change');
    expect(el.textContent).toContain('Cannot reach the server.');
  });

  it('shows the issue to client viewers without the button', async () => {
    const { el } = await render(false);
    expect(el.querySelector('.deps li')?.textContent).toContain('Starts 3 working days too early for this');
    expect(el.querySelector('.issue-fix')?.textContent).toContain('Earliest dates that fit');
    expect(moveButton(el)).toBeUndefined();
  });

  it('says in words where the task was in the baseline, for client viewers too', async () => {
    const { fixture, store, el } = await render(false);
    expect(el.querySelector('.baseline-note')).toBeNull();
    store.comparison.set({
      baseline: { id: 31, projectId: 7, name: 'Signed off', reference: true, createdAt: '', tasks: 2 },
      tasks: [{ taskId: 2, title: build.title, milestone: false, change: 'PULLED_IN', existedThen: true, existsNow: true,
        baselineStart: '2026-03-09', baselineDue: '2026-03-13', currentStart: build.startDate, currentDue: build.dueDate,
        startSlipDays: -1, finishSlipDays: -2 }],
      phases: [], projectEnd: { change: 'UNCHANGED' }, planEnd: { change: 'UNCHANGED' },
      counts: { unchanged: 1, slipped: 0, pulledIn: 1, added: 0, removed: 0 }, baselineEstimateHours: 0, currentEstimateHours: 0,
    });
    await fixture.whenStable();
    expect(el.querySelector('.baseline-note')?.textContent).toContain('Baseline Signed off: Finishes 2 working days earlier than in the baseline');
  });

  it('says how the task waits, and changes the type and the lag from the keyboard', async () => {
    const { fixture, store, el } = await render(true);
    expect(el.querySelector('.deps .dep-phrase')?.textContent).toContain('This starts after Design ends.');

    const type = el.querySelector<HTMLSelectElement>('.deps .dep-edit select')!;
    type.value = 'SS';
    type.dispatchEvent(new Event('change'));
    const patch = http.expectOne('/api/dependencies/50');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ type: 'SS' });
    patch.flush({ task: { ...build, predecessors: [{ ...build.predecessors[0], type: 'SS' }] }, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush({ durationDays: 0, criticalTaskIds: [], tasks: [] });
    http.expectOne('/api/projects/7/schedule-issues').flush([]);
    await fixture.whenStable();
    expect(el.querySelector('.deps .dep-phrase')?.textContent).toContain('This starts with Design.');

    // A lead is a negative lag. A box left empty sends nothing and shows the lag again.
    const lag = el.querySelector<HTMLInputElement>('.deps .dep-edit input')!;
    lag.value = '';
    lag.dispatchEvent(new Event('change'));
    expect(lag.value).toBe('0');
    lag.value = '-2';
    lag.dispatchEvent(new Event('change'));
    const second = http.expectOne('/api/dependencies/50');
    expect(second.request.body).toEqual({ lagDays: -2 });
    second.flush({ code: 'validation_failed', message: 'lagDays must be at least -365' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    // Refused: the box shows what the store holds, and the reason is on screen.
    expect(lag.value).toBe('0');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('lagDays must be at least -365');
    expect(store.byId().get(2)?.predecessors[0].type).toBe('SS');
  });

  it('adds a link with a type and a lead', async () => {
    const { fixture, store, el } = await render(true);
    store.tasks.update((all) => [...all, task(4, 'Copy', '2026-03-09', '2026-03-10')]);
    await fixture.whenStable();
    const form = el.querySelector<HTMLFormElement>('.dep-form')!;
    const [predecessor, type] = [...form.querySelectorAll('select')];
    predecessor.selectedIndex = [...predecessor.options].findIndex((o) => o.textContent?.includes('Copy'));
    predecessor.dispatchEvent(new Event('change'));
    type.selectedIndex = 2;
    type.dispatchEvent(new Event('change'));
    const lag = form.querySelector<HTMLInputElement>('input[name="lag"]')!;
    lag.value = '-1';
    lag.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    form.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const post = http.expectOne('/api/tasks/2/dependencies');
    expect(post.request.body).toEqual({ predecessorId: 4, lagDays: -1, type: 'FF' });
  });
});

