import { Phase, Task } from '../../core/models';
import { DAY_WIDTH, HEADER_HEIGHT, ROW_HEIGHT, barSpan, dateRange, dragDays, layoutGantt, linkPath, xOf } from './gantt-layout';

function task(id: number, startDate?: string, dueDate?: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

describe('gantt layout', () => {
  it('starts the range on a Monday a few days before the first task', () => {
    const range = dateRange([task(1, '2026-03-04', '2026-03-10')]);
    expect(range.start).toBe('2026-02-23');
    expect(range.end).toBe('2026-03-24');
  });

  it('makes a one-day task exactly one day wide', () => {
    expect(barSpan('2026-03-03', '2026-03-03', '2026-03-02', 28)).toEqual({ x: 28, width: 28 });
    expect(barSpan('2026-03-02', '2026-03-06', '2026-03-02', 28)).toEqual({ x: 0, width: 140 });
  });

  it('places dates by day column', () => {
    expect(xOf('2026-03-09', '2026-03-02', 10)).toBe(70);
  });

  it('routes links forward, or around when the successor starts earlier', () => {
    expect(linkPath(100, 10, 200, 42)).toBe('M 100 10 H 108 V 42 H 200');
    expect(linkPath(100, 10, 90, 42)).toBe(`M 100 10 H 108 V ${10 + ROW_HEIGHT / 2} H 82 V 42 H 90`);
  });

  it('converts drag distance to whole days', () => {
    expect(dragDays(41, 28)).toBe(1);
    expect(dragDays(-15, 28)).toBe(-1);
    expect(dragDays(10, 28)).toBe(0);
  });

  it('lays out phases, tasks, bars and links', () => {
    const phases: Phase[] = [{ id: 7, projectId: 1, name: 'Build', position: 0 }];
    const a = task(1, '2026-03-02', '2026-03-03', { phaseId: 7 });
    const b = task(2, '2026-03-04', '2026-03-06', {
      phaseId: 7,
      predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }],
    });
    const m = task(3, '2026-03-09', '2026-03-09', { milestone: true });
    const layout = layoutGantt([a, b, m], phases, { zoom: 'day', critical: new Set([1, 2]), collapsed: new Set(), today: '2026-03-04' });

    expect(layout.rows.map((r) => r.label)).toEqual(['Build', 'Task 1', 'Task 2', 'No phase', 'Task 3']);
    expect(layout.rows[0].y).toBe(HEADER_HEIGHT);
    expect(layout.bars).toHaveLength(3);
    const barB = layout.bars.find((x) => x.task.id === 2)!;
    expect(barB.width).toBe(3 * DAY_WIDTH.day);
    expect(barB.critical).toBe(true);
    expect(layout.bars.find((x) => x.task.id === 3)!.milestone).toBe(true);
    expect(layout.links).toEqual([expect.objectContaining({ id: 50, critical: true })]);
    expect(layout.todayX).not.toBeNull();
    expect(layout.weekends.length).toBeGreaterThan(0);
  });

});
