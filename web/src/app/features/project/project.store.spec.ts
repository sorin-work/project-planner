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
const build = task(2, '2026-03-04', '2026-03-06', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }] });
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
