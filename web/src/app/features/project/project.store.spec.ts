import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ScheduleIssue, Task } from '../../core/models';
import { ProjectStore } from './project.store';
import { linkKey } from './schedule-issues';

function task(id: number, startDate: string, dueDate: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 7, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

const design = task(1, '2026-03-02', '2026-03-06');
const build = task(2, '2026-03-04', '2026-03-06', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }] });
const early: ScheduleIssue = {
  type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-11',
};
const noPath = { durationDays: 0, criticalTaskIds: [], tasks: [] };

describe('ProjectStore schedule issues', () => {
  let http: HttpTestingController;
  let store: ProjectStore;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), ProjectStore] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProjectStore);
  });

  afterEach(() => http.verify());

  function load(issues: ScheduleIssue[]): void {
    store.load(7).subscribe();
    http.expectOne('/api/projects/7').flush({ id: 7, organisationId: 3, name: 'Site', code: 'SITE', status: 'ACTIVE' });
    http.expectOne('/api/projects/7/tasks').flush([design, build]);
    http.expectOne('/api/projects/7/phases').flush([]);
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush(issues);
    http.expectOne('/api/orgs/3/members').flush([]);
    http.expectOne('/api/orgs/3/holidays').flush([{ id: 1, date: '2026-03-04', name: 'Holiday' }]);
    http.expectOne('/api/projects/7/baselines').flush([]);
  }

  it('loads the issues with the plan', () => {
    load([early]);
    expect(store.issues()).toEqual([early]);
    expect(store.issuesOf(2)).toEqual([early]);
    expect(store.issuesOf(1)).toEqual([]);
    expect([...store.brokenLinks()]).toEqual([linkKey(1, 2)]);
    // The holidays come with the team, for the timeline to shade and skip.
    expect([...store.holidays()]).toEqual(['2026-03-04']);
  });

  it('forgets the holidays of the project before and ignores a late answer for it', () => {
    load([]);
    store.load(8).subscribe();
    expect(store.holidays().size).toBe(0);
    http.expectOne('/api/projects/8').flush({ id: 8, organisationId: 4, name: 'Other', code: 'OTH', status: 'ACTIVE' });
    http.expectOne('/api/projects/8/tasks').flush([]);
    http.expectOne('/api/projects/8/phases').flush([]);
    http.expectOne('/api/projects/8/critical-path').flush(noPath);
    http.expectOne('/api/projects/8/schedule-issues').flush([]);
    http.expectOne('/api/orgs/4/members').flush([]);
    const late = http.expectOne('/api/orgs/4/holidays');
    http.expectOne('/api/projects/8/baselines').flush([]);
    // Meanwhile the first project is opened again, and the answer for the second one arrives late.
    store.load(7).subscribe();
    late.flush([{ id: 9, date: '2026-03-05', name: 'Elsewhere' }]);
    expect(store.holidays().size).toBe(0);
    http.expectOne('/api/projects/7').flush({ id: 7, organisationId: 3, name: 'Site', code: 'SITE', status: 'ACTIVE' });
    http.expectOne('/api/projects/7/tasks').flush([]);
    http.expectOne('/api/projects/7/phases').flush([]);
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([]);
    http.expectOne('/api/orgs/3/members').flush([]);
    http.expectOne('/api/orgs/3/holidays').flush([{ id: 1, date: '2026-03-04', name: 'Holiday' }]);
    http.expectOne('/api/projects/7/baselines').flush([]);
    expect([...store.holidays()]).toEqual(['2026-03-04']);
  });

  it('asks again after a change', () => {
    load([early]);
    const moved = { ...build, startDate: '2026-03-09', dueDate: '2026-03-11' };
    store.updateTask(2, { startDate: '2026-03-09', dueDate: '2026-03-11' }).subscribe();
    http.expectOne('/api/tasks/2').flush({ task: moved, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([]);
    expect(store.issues()).toEqual([]);
    expect(store.brokenLinks().size).toBe(0);
  });

  it('drops the warnings and keeps the reason when they cannot be refreshed after a change', () => {
    load([early]);
    store.shiftTask(1, 5).subscribe();
    http.expectOne('/api/tasks/1/shift').flush({ task: { ...design, startDate: '2026-03-09', dueDate: '2026-03-13' }, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush({ ...noPath, finish: '2026-03-13' });
    http.expectOne('/api/projects/7/schedule-issues').flush('down', { status: 503, statusText: 'Service Unavailable' });

    // The old "Move to" would pull Build back in front of Design, which has just moved a week later.
    expect(store.issues()).toEqual([]);
    expect(store.brokenLinks().size).toBe(0);
    expect(store.criticalPath()).toBeNull();
    expect(store.scheduleError()).toBe('Request failed (503)');

    store.refreshSchedule();
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([early]);
    expect(store.issues()).toEqual([early]);
    expect(store.scheduleError()).toBeNull();
  });

  it('ignores a failure that belongs to an older change', () => {
    load([]);
    store.shiftTask(2, -1).subscribe();
    http.expectOne('/api/tasks/2/shift').flush({ task: build, rescheduled: [] });
    const slowPath = http.expectOne('/api/projects/7/critical-path');
    const slowIssues = http.expectOne('/api/projects/7/schedule-issues');

    store.shiftTask(2, 3).subscribe();
    http.expectOne('/api/tasks/2/shift').flush({ task: build, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([early]);

    slowPath.flush(noPath);
    slowIssues.flush('down', { status: 503, statusText: 'Service Unavailable' });
    expect(store.issues()).toEqual([early]);
    expect(store.scheduleError()).toBeNull();
  });

  it('keeps the answer to the latest change when an older one arrives late', () => {
    load([]);
    store.shiftTask(2, -1).subscribe();
    http.expectOne('/api/tasks/2/shift').flush({ task: build, rescheduled: [] });
    const slowPath = http.expectOne('/api/projects/7/critical-path');
    const slowIssues = http.expectOne('/api/projects/7/schedule-issues');

    store.shiftTask(2, 3).subscribe();
    http.expectOne('/api/tasks/2/shift').flush({ task: build, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush({ ...noPath, finish: '2026-03-11' });
    http.expectOne('/api/projects/7/schedule-issues').flush([]);

    slowPath.flush({ ...noPath, finish: '2026-03-06' });
    slowIssues.flush([early]);
    expect(store.issues()).toEqual([]);
    expect(store.criticalPath()?.finish).toBe('2026-03-11');
  });
});

describe('ProjectStore baselines', () => {
  let http: HttpTestingController;
  let store: ProjectStore;

  const agreed = { id: 30, projectId: 7, name: 'Agreed', reference: false, createdAt: '', tasks: 2 };
  const signedOff = { id: 31, projectId: 7, name: 'Signed off', reference: true, createdAt: '', tasks: 2 };
  const comparison = (id: number) => ({
    baseline: id === 30 ? agreed : signedOff, tasks: [], phases: [], projectEnd: { change: 'UNCHANGED' }, planEnd: { change: 'UNCHANGED' },
    counts: { unchanged: 2, slipped: 0, pulledIn: 0, added: 0, removed: 0 }, baselineEstimateHours: 0, currentEstimateHours: 0,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), ProjectStore] });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProjectStore);
    store.load(7).subscribe();
    http.expectOne('/api/projects/7').flush({ id: 7, organisationId: 3, name: 'Site', code: 'SITE', status: 'ACTIVE' });
    http.expectOne('/api/projects/7/tasks').flush([design, build]);
    http.expectOne('/api/projects/7/phases').flush([]);
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([]);
    http.expectOne('/api/orgs/3/members').flush([]);
    http.expectOne('/api/orgs/3/holidays').flush([]);
    http.expectOne('/api/projects/7/baselines').flush([agreed, signedOff]);
  });

  afterEach(() => http.verify());

  it('compares with the reference baseline from the start', () => {
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    expect(store.comparedId()).toBe(31);
    expect(store.comparison()?.baseline.name).toBe('Signed off');
  });

  it('asks for the comparison again after a change to the plan, so the thin bars follow a drag', () => {
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    store.shiftTask(1, 2).subscribe();
    http.expectOne('/api/tasks/1/shift').flush({ task: { ...design, startDate: '2026-03-04', dueDate: '2026-03-10' }, rescheduled: [] });
    http.expectOne('/api/projects/7/critical-path').flush(noPath);
    http.expectOne('/api/projects/7/schedule-issues').flush([]);
    http.expectOne('/api/baselines/31/comparison').flush({ ...comparison(31), counts: { unchanged: 1, slipped: 1, pulledIn: 0, added: 0, removed: 0 } });
    expect(store.comparison()?.counts.slipped).toBe(1);
  });

  it('drops the comparison and keeps the reason when it cannot be fetched, and an older answer does not count', () => {
    const first = http.expectOne('/api/baselines/31/comparison');
    store.compareWith(30);
    const second = http.expectOne('/api/baselines/30/comparison');
    first.flush(comparison(31));
    expect(store.comparison()).toBeNull();
    second.flush('down', { status: 503, statusText: 'Service Unavailable' });
    expect(store.comparison()).toBeNull();
    expect(store.comparisonError()).toBeTruthy();

    store.compareWith(null);
    expect(store.comparisonError()).toBeNull();
  });

  it('stays on the baseline that was picked when the list is fetched again, unless it was deleted', () => {
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    store.compareWith(30);
    http.expectOne('/api/baselines/30/comparison').flush(comparison(30));
    store.loadBaselines();
    http.expectOne('/api/projects/7/baselines').flush([agreed, signedOff]);
    http.expectOne('/api/baselines/30/comparison').flush(comparison(30));
    expect(store.comparedId()).toBe(30);
    store.loadBaselines();
    http.expectOne('/api/projects/7/baselines').flush([{ ...signedOff }]);
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    expect(store.comparedId()).toBe(31);
  });

  it('compares with the first baseline as soon as the project has one', () => {
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    store.baselines.set([]);
    store.compareWith(null);
    store.loadBaselines();
    http.expectOne('/api/projects/7/baselines').flush([signedOff]);
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    expect(store.comparedId()).toBe(31);
  });

  it('forgets the baselines of the project before when another one loads, and keeps nothing when they cannot be fetched', () => {
    http.expectOne('/api/baselines/31/comparison').flush(comparison(31));
    expect(store.comparison()).not.toBeNull();

    store.load(8).subscribe();
    // Before anything of the new project has arrived, nothing of the old one is left.
    expect(store.baselines()).toEqual([]);
    expect(store.comparedId()).toBeNull();
    expect(store.comparison()).toBeNull();
    http.expectOne('/api/projects/8').flush({ id: 8, organisationId: 3, name: 'Other', code: 'OTHER', status: 'ACTIVE' });
    http.expectOne('/api/projects/8/tasks').flush([]);
    http.expectOne('/api/projects/8/phases').flush([]);
    http.expectOne('/api/projects/8/critical-path').flush(noPath);
    http.expectOne('/api/projects/8/schedule-issues').flush([]);
    http.expectOne('/api/orgs/3/members').flush([]);
    http.expectOne('/api/orgs/3/holidays').flush([]);
    http.expectOne('/api/projects/8/baselines').flush('down', { status: 503, statusText: 'Service Unavailable' });
    expect(store.baselines()).toEqual([]);
    expect(store.comparison()).toBeNull();
    expect(store.comparisonError()).toBeTruthy();
  });

  it('drops a comparison of the project before that arrives after another one was loaded', () => {
    const late = http.expectOne('/api/baselines/31/comparison');
    store.load(8).subscribe();
    late.flush(comparison(31));
    expect(store.comparison()).toBeNull();
    http.expectOne('/api/projects/8').flush({ id: 8, organisationId: 3, name: 'Other', code: 'OTHER', status: 'ACTIVE' });
    http.expectOne('/api/projects/8/tasks').flush([]);
    http.expectOne('/api/projects/8/phases').flush([]);
    http.expectOne('/api/projects/8/critical-path').flush(noPath);
    http.expectOne('/api/projects/8/schedule-issues').flush([]);
    http.expectOne('/api/orgs/3/members').flush([]);
    http.expectOne('/api/orgs/3/holidays').flush([]);
    http.expectOne('/api/projects/8/baselines').flush([]);
    expect(store.comparison()).toBeNull();
  });
});

