import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { Project, ScheduleIssue, Task } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { Gantt } from '../gantt/gantt';
import { ProjectStore } from './project.store';
import { linkKey } from './schedule-issues';
import { TimelineTab } from './timeline.tab';

function task(id: number, title: string, startDate: string, dueDate: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 7, title, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

const design = task(1, 'Design', '2026-03-02', '2026-03-06');
const build = task(2, 'Build', '2026-03-09', '2026-03-11', {
  predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }],
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

  it('sends a move as working days', async () => {
    gantt.moved.emit({ taskId: 1, days: -1 });
    const shift = http.expectOne('/api/tasks/1/shift');
    expect(shift.request.body).toEqual({ days: -1 });
    shift.flush({ task: { ...design, startDate: '2026-02-27', dueDate: '2026-03-05' }, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(store.byId().get(1)?.startDate).toBe('2026-02-27');
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
    gantt.linked.emit({ predecessorId: 1, successorId: 2 });
    const post = http.expectOne('/api/tasks/2/dependencies');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ predecessorId: 1, lagDays: 0 });
    post.flush({ task: build, rescheduled: [] });
    refetch([]);
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Build now waits for Design.');
    expect(store.byId().get(2)?.predecessors).toHaveLength(1);
  });

  it('shows the loop the API found when a drawn link would close one', async () => {
    gantt.linked.emit({ predecessorId: 2, successorId: 1 });
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
    expect(el.querySelector('[role=status]')?.textContent).toContain('Build no longer waits for Design.');
    expect(store.byId().get(2)?.predecessors).toHaveLength(0);
  });
});
