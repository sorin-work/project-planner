import { Task } from '../../core/models';
import { NO_FILTER, TaskFilter, assigneesOf, filterFromParams, filterRows, filterToParams, isFiltering, matchesFilter } from './task-filter';

function task(id: number, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '2026-02-20T09:00:00Z', updatedAt: '2026-02-20T09:00:00Z', predecessors: [], subtaskCount: 0, loggedHours: 0,
    ...extra,
  };
}

const params = (query: string) => new URLSearchParams(query);
const filter = (extra: Partial<TaskFilter>): TaskFilter => ({ ...NO_FILTER, ...extra });

describe('the List tab filter in the address', () => {
  it('reads every part of it', () => {
    expect(filterFromParams(params('q=logo&assignee=7&status=IN_PROGRESS&priority=HIGH&done=hide&mine=1'))).toEqual({
      text: 'logo', assignee: 7, status: 'IN_PROGRESS', priority: 'HIGH', hideDone: true, mineOnly: true,
    });
    expect(filterFromParams(params('assignee=none')).assignee).toBe('none');
    expect(filterFromParams(params('task=12'))).toEqual(NO_FILTER);
  });

  it('ignores values it does not know instead of filtering everything away', () => {
    expect(filterFromParams(params('assignee=me&status=NOPE&priority=7&done=1&mine=yes'))).toEqual(NO_FILTER);
  });

  it('writes only what is set, and takes the rest out of the address', () => {
    expect(filterToParams(filter({ text: ' logo ', assignee: 'none', hideDone: true }))).toEqual({
      q: 'logo', assignee: 'none', status: null, priority: null, done: 'hide', mine: null,
    });
    expect(filterToParams(NO_FILTER)).toEqual({ q: null, assignee: null, status: null, priority: null, done: null, mine: null });
  });

  it('comes back the same after a round trip', () => {
    const set = filter({ text: 'logo', assignee: 7, status: 'DONE', priority: 'URGENT', mineOnly: true });
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(filterToParams(set))) {
      if (value !== null) {
        query.set(key, value);
      }
    }
    expect(filterFromParams(query)).toEqual(set);
  });

  it('knows whether anything is filtered', () => {
    expect(isFiltering(NO_FILTER)).toBe(false);
    expect(isFiltering(filter({ text: '  ' }))).toBe(false);
    expect(isFiltering(filter({ priority: 'LOW' }))).toBe(true);
  });
});

describe('matchesFilter', () => {
  const logo = task(1, { title: 'Draw the Logo', description: 'Vector, two colours', assigneeId: 7, status: 'IN_PROGRESS', priority: 'HIGH' });

  it('finds text in the title and the description, whatever the case', () => {
    expect(matchesFilter(logo, filter({ text: 'logo' }), undefined)).toBe(true);
    expect(matchesFilter(logo, filter({ text: 'VECTOR' }), undefined)).toBe(true);
    expect(matchesFilter(logo, filter({ text: 'poster' }), undefined)).toBe(false);
  });

  it('filters by assignee, by nobody, by status and by priority', () => {
    expect(matchesFilter(logo, filter({ assignee: 7 }), undefined)).toBe(true);
    expect(matchesFilter(logo, filter({ assignee: 8 }), undefined)).toBe(false);
    expect(matchesFilter(logo, filter({ assignee: 'none' }), undefined)).toBe(false);
    expect(matchesFilter(task(2), filter({ assignee: 'none' }), undefined)).toBe(true);
    expect(matchesFilter(logo, filter({ status: 'IN_PROGRESS', priority: 'HIGH' }), undefined)).toBe(true);
    expect(matchesFilter(logo, filter({ status: 'DONE' }), undefined)).toBe(false);
    expect(matchesFilter(logo, filter({ priority: 'LOW' }), undefined)).toBe(false);
  });

  it('keeps "Hide done" and "Only mine" as they were', () => {
    expect(matchesFilter(task(3, { status: 'DONE' }), filter({ hideDone: true }), undefined)).toBe(false);
    expect(matchesFilter(logo, filter({ mineOnly: true }), 7)).toBe(true);
    expect(matchesFilter(logo, filter({ mineOnly: true }), 8)).toBe(false);
    expect(matchesFilter(logo, filter({ mineOnly: true }), undefined)).toBe(false);
  });
});

describe('filterRows', () => {
  const rows = [
    { task: task(1, { title: 'Website' }), depth: 0 },
    { task: task(2, { title: 'Homepage', parentId: 1 }), depth: 1 },
    { task: task(3, { title: 'Contact form', parentId: 1 }), depth: 1 },
    { task: task(4, { title: 'Print the form' }), depth: 0 },
    { task: task(5, { title: 'Newsletter' }), depth: 0 },
  ];

  it('keeps everything without a filter', () => {
    expect(filterRows(rows, NO_FILTER, undefined).map((r) => [r.task.id, r.context])).toEqual([
      [1, false], [2, false], [3, false], [4, false], [5, false],
    ]);
  });

  it('keeps the parent of a subtask that matches, marked as context', () => {
    expect(filterRows(rows, filter({ text: 'form' }), undefined).map((r) => [r.task.id, r.context])).toEqual([
      [1, true], [3, false], [4, false],
    ]);
  });

  it('does not mark a parent that matches itself, and shows it once', () => {
    expect(filterRows(rows, filter({ text: 'e' }), undefined).map((r) => [r.task.id, r.context])).toEqual([
      [1, false], [2, false], [4, false], [5, false],
    ]);
  });

  it('shows nothing of a task when neither it nor a subtask matches', () => {
    expect(filterRows(rows, filter({ text: 'newsletter' }), undefined).map((r) => r.task.id)).toEqual([5]);
  });
});

describe('assigneesOf', () => {
  it('lists everyone who has a task once, by name', () => {
    const tasks = [task(1, { assigneeId: 7, assigneeName: 'Tom' }), task(2, { assigneeId: 3, assigneeName: 'Lena' }), task(3, { assigneeId: 7, assigneeName: 'Tom' }), task(4)];
    expect(assigneesOf(tasks)).toEqual([{ id: 3, name: 'Lena' }, { id: 7, name: 'Tom' }]);
  });
});
