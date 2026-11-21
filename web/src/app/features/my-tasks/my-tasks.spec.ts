import { SearchHit } from '../../core/models';
import { groupMyTasks, groupOf } from './my-tasks';

function task(id: number, dueDate?: string, status: SearchHit['status'] = 'TODO'): SearchHit {
  return { type: 'TASK', id, projectId: 1, projectCode: 'WEB', taskId: id, title: `Task ${id}`, dueDate, status, priority: 'MEDIUM' };
}

describe('my tasks groups', () => {
  // A Wednesday: this week runs to Sunday the 29th.
  const today = '2026-11-25';

  it('puts a task in its group by the organisation date', () => {
    expect(groupOf(task(1, '2026-11-24'), today)).toBe('overdue');
    expect(groupOf(task(2, '2026-11-25'), today)).toBe('week');
    expect(groupOf(task(3, '2026-11-29'), today)).toBe('week');
    expect(groupOf(task(4, '2026-11-30'), today)).toBe('later');
    expect(groupOf(task(5), today)).toBe('none');
  });

  it('never calls a done task overdue', () => {
    expect(groupOf(task(1, '2026-01-01', 'DONE'), today)).toBe('done');
    expect(groupOf(task(2, undefined, 'DONE'), today)).toBe('done');
  });

  it('on a Sunday this week is that day alone', () => {
    expect(groupOf(task(1, '2026-11-29'), '2026-11-29')).toBe('week');
    expect(groupOf(task(2, '2026-11-30'), '2026-11-29')).toBe('later');
  });

  it('keeps the order the tasks came in and leaves out empty groups', () => {
    const groups = groupMyTasks([task(1, '2026-11-20'), task(2, '2026-12-10'), task(3), task(4, '2026-11-21'), task(5, '2026-12-01')], today);
    expect(groups.map((g) => g.label)).toEqual(['Overdue', 'Later', 'No date']);
    expect(groups[0].tasks.map((t) => t.id)).toEqual([1, 4]);
    expect(groups[1].tasks.map((t) => t.id)).toEqual([2, 5]);
    expect(groupMyTasks([], today)).toEqual([]);
  });
});
