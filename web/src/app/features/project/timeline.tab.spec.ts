import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { BaselineComparison, Project, ScheduleIssue, Task } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { Gantt } from '../gantt/gantt';
import { ProjectStore } from './project.store';
import { linkKey } from './schedule-issues';
import { MOVE_PAUSE_MS, TimelineTab } from './timeline.tab';
import { Announcer } from '../../core/announcer';

function task(id: number, title: string, startDate: string, dueDate: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 7, title, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

const design = task(1, 'Design', '2026-03-02', '2026-03-06');
const build = task(2, 'Build', '2026-03-09', '2026-03-11', {
  predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }],
});
const noPath = { durationDays: 0, criticalTaskIds: [], tasks: [] };

describe('TimelineTab edits', () => {
  let http: HttpTestingController;
  let store: ProjectStore;
  let fixture: ComponentFixture<TimelineTab>;
  let el: HTMLElement;
  let gantt: Gantt;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [TimelineTab],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ProjectStore,
        { provide: OrgContext, useValue: { isTeam: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProjectStore);
    store.project.set({ id: 7, organisationId: 3 } as Project);
    store.tasks.set([design, build]);
    fixture = TestBed.createComponent(TimelineTab);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
    gantt = fixture.debugElement.query(By.directive(Gantt)).componentInstance as Gantt;
  });

  afterEach(() => http.verify());

  function refetch(issues: ScheduleIssue[]): void {
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush(issues);
  }

  /** Lets the pause pass after which moves are sent. */
  const pause = () => new Promise((resolve) => setTimeout(resolve, MOVE_PAUSE_MS + 20));

  it('sends a move as working days, after a short pause', async () => {
    gantt.moved.emit({ taskId: 1, days: -1 });
    http.expectNone('/api/tasks/1/shift');
    await pause();
    const shift = http.expectOne('/api/tasks/1/shift');
    expect(shift.request.body).toEqual({ days: -1 });
    shift.flush({ task: { ...design, startDate: '2026-02-27', dueDate: '2026-03-05' }, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(store.byId().get(1)?.startDate).toBe('2026-02-27');
  });

  it('adds up the presses of a held arrow key into one move, saved and read out once', async () => {
    const announcer = TestBed.inject(Announcer);
    const said = vi.spyOn(announcer, 'announce');
    for (let i = 0; i < 4; i++) {
      gantt.moved.emit({ taskId: 1, days: 1 });
    }
    gantt.moved.emit({ taskId: 1, days: -1 });
    await pause();
    const shift = http.expectOne('/api/tasks/1/shift');
    expect(shift.request.body).toEqual({ days: 3 });
    shift.flush({ task: { ...design, startDate: '2026-03-05', dueDate: '2026-03-11' }, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(said).toHaveBeenCalledTimes(1);
    expect(said.mock.calls[0][0]).toMatch(/^Design: /);
    // The note under the toolbar is not a live region of its own, so the move is not read twice.
    expect(el.querySelector('.alert.info[role]')).toBeNull();

    // Presses that come back to where the bar was send nothing.
    gantt.moved.emit({ taskId: 1, days: 1 });
    gantt.moved.emit({ taskId: 1, days: -1 });
    await pause();
    http.expectNone('/api/tasks/1/shift');
  });

  it('sends only the date of the end that was dragged, and shows the warning the resize caused at once', async () => {
    gantt.resized.emit({ taskId: 2, startDate: '2026-03-04' });
    const patch = http.expectOne('/api/tasks/2');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ startDate: '2026-03-04' });
    patch.flush({ task: { ...build, startDate: '2026-03-04' }, rescheduled: [] });

    // Build now starts while Design is still running. The issues are asked for again straight away.
    refetch([{ type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-16' }]);
    await fixture.whenStable();
    expect(el.querySelector('.toolbar button.danger')?.textContent).toContain('1 schedule issue');
    expect(store.brokenLinks().has(linkKey(1, 2))).toBe(true);
    expect(el.querySelector('path.link.broken')).not.toBeNull();
  });

  it('counts every Shift and arrow press, also when they come faster than the answers', async () => {
    // Build is due on Wednesday 11 March. Three presses before anything has come back.
    gantt.extended.emit({ taskId: 2, days: 1 });
    gantt.extended.emit({ taskId: 2, days: 1 });
    gantt.extended.emit({ taskId: 2, days: 1 });

    // One request is out. The others wait, and what goes next is the latest date: Monday, past the weekend.
    const first = http.expectOne('/api/tasks/2');
    expect(first.request.body).toEqual({ dueDate: '2026-03-12' });
    first.flush({ task: { ...build, dueDate: '2026-03-12' }, rescheduled: [] });
    refetch([]);
    const second = http.expectOne('/api/tasks/2');
    expect(second.request.body).toEqual({ dueDate: '2026-03-16' });
    second.flush({ task: { ...build, dueDate: '2026-03-16' }, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(store.byId().get(2)?.dueDate).toBe('2026-03-16');

    // With nothing on its way the next press counts from what the API answered.
    gantt.extended.emit({ taskId: 2, days: -1 });
    const third = http.expectOne('/api/tasks/2');
    expect(third.request.body).toEqual({ dueDate: '2026-03-13' });
    third.flush({ task: { ...build, dueDate: '2026-03-13' }, rescheduled: [] });
    refetch([]);
  });

  it('counts every Alt and arrow press on the start date the same way', async () => {
    // Build starts on Monday 9 March. Two presses back, before anything has come back.
    gantt.started.emit({ taskId: 2, days: -1 });
    gantt.started.emit({ taskId: 2, days: -1 });

    // Back past the weekend is Friday; the press after that waits and asks for Thursday.
    const first = http.expectOne('/api/tasks/2');
    expect(first.request.body).toEqual({ startDate: '2026-03-06' });
    first.flush({ task: { ...build, startDate: '2026-03-06' }, rescheduled: [] });
    refetch([]);
    const second = http.expectOne('/api/tasks/2');
    expect(second.request.body).toEqual({ startDate: '2026-03-05' });
    second.flush({ task: { ...build, startDate: '2026-03-05' }, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(store.byId().get(2)?.startDate).toBe('2026-03-05');
  });

  it('does not count on from a due date the API refused', async () => {
    gantt.extended.emit({ taskId: 2, days: 1 });
    http.expectOne('/api/tasks/2').flush(
      { status: 409, code: 'project_done', message: 'This project is finished' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('This project is finished');

    gantt.extended.emit({ taskId: 2, days: 1 });
    const again = http.expectOne('/api/tasks/2');
    expect(again.request.body).toEqual({ dueDate: '2026-03-12' });
    again.flush({ task: { ...build, dueDate: '2026-03-12' }, rescheduled: [] });
    refetch([]);
  });

  it('counts the start keys from the stored start again after a refusal, and drops what was counted on it', async () => {
    gantt.started.emit({ taskId: 2, days: -1 });
    gantt.started.emit({ taskId: 2, days: -1 });
    http.expectOne('/api/tasks/2').flush(
      { status: 409, code: 'project_done', message: 'This project is finished' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    // The second press was counted from Friday, which was never saved: it does not go out.
    http.expectNone('/api/tasks/2');

    gantt.started.emit({ taskId: 2, days: -1 });
    const again = http.expectOne('/api/tasks/2');
    expect(again.request.body).toEqual({ startDate: '2026-03-06' });
    again.flush({ task: { ...build, startDate: '2026-03-06' }, rescheduled: [] });
    refetch([]);
  });

  it('stops the due date at the start, however often the key is pressed', async () => {
    // Build runs Monday 9 to Wednesday 11: two presses back and it is a one-day task. The rest do nothing.
    for (let press = 0; press < 5; press++) {
      gantt.extended.emit({ taskId: 2, days: -1 });
    }
    const first = http.expectOne('/api/tasks/2');
    expect(first.request.body).toEqual({ dueDate: '2026-03-10' });
    first.flush({ task: { ...build, dueDate: '2026-03-10' }, rescheduled: [] });
    refetch([]);
    const second = http.expectOne('/api/tasks/2');
    expect(second.request.body).toEqual({ dueDate: '2026-03-09' });
    second.flush({ task: { ...build, dueDate: '2026-03-09' }, rescheduled: [] });
    refetch([]);
  });

  it('sends two quick drags on one task one after the other', async () => {
    gantt.resized.emit({ taskId: 2, dueDate: '2026-03-13' });
    gantt.resized.emit({ taskId: 2, startDate: '2026-03-10' });
    const first = http.expectOne('/api/tasks/2');
    expect(first.request.body).toEqual({ dueDate: '2026-03-13' });
    first.flush({ task: { ...build, dueDate: '2026-03-13' }, rescheduled: [] });
    refetch([]);
    const second = http.expectOne('/api/tasks/2');
    expect(second.request.body).toEqual({ startDate: '2026-03-10' });
    second.flush({ task: { ...build, startDate: '2026-03-10', dueDate: '2026-03-13' }, rescheduled: [] });
    refetch([]);
  });

  it('shows no warnings rather than old ones when they cannot be refreshed after a drag', async () => {
    store.issues.set([{ type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-11' }]);
    await fixture.whenStable();
    expect(el.querySelector('path.link.broken')).not.toBeNull();

    gantt.moved.emit({ taskId: 1, days: 5 });
    await pause();
    http.expectOne('/api/tasks/1/shift').flush({ task: { ...design, startDate: '2026-03-09', dueDate: '2026-03-13' }, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush('down', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    expect(el.querySelector('path.link.broken')).toBeNull();
    expect(el.querySelector('.toolbar button.danger')).toBeNull();
    const alert = el.querySelector('[role=alert]')!;
    expect(alert.textContent).toContain('The schedule could not be checked after the last change');
    expect(alert.textContent).toContain('Request failed (503)');

    alert.querySelector('button')!.click();
    refetch([]);
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('leaves the bar where it was when the API refuses a resize, and says why', async () => {
    gantt.resized.emit({ taskId: 2, dueDate: '2026-03-05' });
    http.expectOne('/api/tasks/2').flush(
      { status: 400, code: 'invalid_dates', message: "The due date can't be before the start date" },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("The due date can't be before the start date");
    expect(store.tasks()).toEqual([design, build]);
    // Task 2 is still drawn from Monday 9 March: fourteen columns from the chart's first day.
    expect(el.querySelectorAll('g.bar')[1].querySelector('.bar-bg')?.getAttribute('x')).toBe(String(14 * 28 + 1));
  });

  it('draws a link through the dependency endpoint of the task panel', async () => {
    store.tasks.set([design, { ...build, predecessors: [] }]);
    gantt.linked.emit({ predecessorId: 1, successorId: 2, type: 'SS' });
    const post = http.expectOne('/api/tasks/2/dependencies');
    expect(post.request.method).toBe('POST');
    // Of the type picked in the menu after the drop.
    expect(post.request.body).toEqual({ predecessorId: 1, lagDays: 0, type: 'SS' });
    post.flush({ task: build, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(el.querySelector('.alert.info')?.textContent).toContain('Build now waits for Design.');
    expect(store.byId().get(2)?.predecessors).toHaveLength(1);
  });

  it('shows the loop the API found when a drawn link would close one', async () => {
    gantt.linked.emit({ predecessorId: 2, successorId: 1, type: 'FS' });
    http.expectOne('/api/tasks/1/dependencies').flush(
      { status: 409, code: 'dependency_cycle', message: 'That would create a loop: Design → Build → Design' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('That would create a loop: Design → Build → Design');
    expect(store.byId().get(1)?.predecessors).toHaveLength(0);
  });

  it('removes a link and refreshes the plan', async () => {
    gantt.unlinked.emit({ successorId: 2, dependencyId: 50 });
    const del = http.expectOne('/api/dependencies/50');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    refetch([]);
    await fixture.whenStable();
    expect(el.querySelector('.alert.info')?.textContent).toContain('Build no longer waits for Design.');
    expect(store.byId().get(2)?.predecessors).toHaveLength(0);
  });
});

describe('TimelineTab with a baseline', () => {
  const signedOff = { id: 31, projectId: 7, name: 'Signed off', reference: true, createdAt: '', tasks: 3 };
  const comparison: BaselineComparison = {
    baseline: signedOff,
    tasks: [
      { taskId: 1, title: 'Design', milestone: false, change: 'UNCHANGED', existedThen: true, existsNow: true,
        baselineStart: '2026-03-02', baselineDue: '2026-03-06', currentStart: '2026-03-02', currentDue: '2026-03-06', startSlipDays: 0, finishSlipDays: 0 },
      { taskId: 2, title: 'Build', milestone: false, change: 'SLIPPED', existedThen: true, existsNow: true,
        baselineStart: '2026-03-09', baselineDue: '2026-03-10', currentStart: '2026-03-09', currentDue: '2026-03-11', startSlipDays: 0, finishSlipDays: 1 },
      { taskId: 9, title: 'Old copy', milestone: false, change: 'REMOVED', existedThen: true, existsNow: false,
        baselineStart: '2026-03-02', baselineDue: '2026-03-03' },
    ],
    phases: [],
    projectEnd: { change: 'UNCHANGED' },
    planEnd: { baseline: '2026-03-10', current: '2026-03-11', slipDays: 1, change: 'SLIPPED' },
    counts: { unchanged: 1, slipped: 1, pulledIn: 0, added: 0, removed: 1 },
    baselineEstimateHours: 0,
    currentEstimateHours: 0,
  };

  let http: HttpTestingController;
  let store: ProjectStore;
  let fixture: ComponentFixture<TimelineTab>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [TimelineTab],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ProjectStore,
        { provide: OrgContext, useValue: { isTeam: () => false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProjectStore);
    store.project.set({ id: 7, organisationId: 3 } as Project);
    store.tasks.set([design, build]);
    store.baselines.set([{ ...signedOff, id: 30, name: 'Agreed', reference: false }, signedOff]);
    store.comparedId.set(31);
    store.comparison.set(comparison);
    fixture = TestBed.createComponent(TimelineTab);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  it('draws a thin bar for every task that is still there and reads out how it compares', () => {
    expect(el.querySelectorAll('rect.baseline-bar')).toHaveLength(2);
    const bar = el.querySelector('g.bar[data-task="2"]')!;
    expect(bar.getAttribute('aria-label')).toContain('Finishes 1 working day later than in the baseline');
    expect(el.querySelector('.baseline-summary')!.textContent).toContain('Grey bars: Signed off. 1 slipped, 1 removed.');
  });

  it('lists the changes, a deleted task included, without the ones that stayed', async () => {
    [...el.querySelectorAll<HTMLButtonElement>('.baseline-summary button')][0].click();
    await fixture.whenStable();
    const lines = [...el.querySelectorAll('[aria-label="Changes since the baseline"] li')].map((li) => li.textContent!.replace(/\s+/g, ' ').trim());
    expect(lines).toHaveLength(2);
    expect(lines[0]).toMatch(/^Build ?Finishes 1 working day later/);
    expect(lines[1]).toMatch(/^Old copy ?Removed since the baseline/);
  });

  it('compares with another baseline or with none from the toolbar', async () => {
    const select = el.querySelector<HTMLSelectElement>('.toolbar select')!;
    expect([...select.options].map((o) => o.textContent!.trim())).toEqual(['No baseline', 'Agreed', 'Signed off (reference)']);
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    http.expectOne('/api/baselines/30/comparison').flush({ ...comparison, baseline: { ...signedOff, id: 30, name: 'Agreed' } });
    await fixture.whenStable();
    expect(el.querySelector('.baseline-summary')!.textContent).toContain('Grey bars: Agreed.');
    select.selectedIndex = 0;
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(el.querySelectorAll('rect.baseline-bar')).toHaveLength(0);
    expect(el.querySelector('.baseline-summary')).toBeNull();
  });
});
