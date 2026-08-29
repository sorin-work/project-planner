import { TimeEntry } from '../../core/models';
import { buildGrid } from './timesheet-grid';
import { formatElapsed } from './timer.service';

function entry(id: number, date: string, minutes: number, extra: Partial<TimeEntry> = {}): TimeEntry {
  return {
    id, userId: 1, userName: 'A', projectId: 1, projectName: 'Website', projectCode: 'WEB', date, minutes,
    billable: true, running: false, locked: false, ...extra,
  };
}

describe('buildGrid', () => {
  it('sums minutes per row and day', () => {
    const grid = buildGrid('2026-03-02', [
      entry(1, '2026-03-02', 60, { taskId: 5, taskTitle: 'Design' }),
      entry(2, '2026-03-02', 30, { taskId: 5, taskTitle: 'Design' }),
      entry(3, '2026-03-04', 120),
      entry(4, '2026-03-08', 15, { projectId: 2, projectCode: 'ADM', projectName: 'Admin', billable: false }),
    ]);
    expect(grid.dates).toHaveLength(7);
    expect(grid.rows.map((r) => r.key)).toEqual(['2:', '1:', '1:5']);
    expect(grid.rows[2].days).toEqual([90, 0, 0, 0, 0, 0, 0]);
    expect(grid.rows[0].billable).toBe(false);
    expect(grid.dayTotals).toEqual([90, 0, 120, 0, 0, 0, 15]);
    expect(grid.total).toBe(225);
  });

  it('ignores entries outside the week', () => {
    const grid = buildGrid('2026-03-02', [entry(1, '2026-03-09', 60)]);
    expect(grid.rows).toHaveLength(0);
    expect(grid.total).toBe(0);
  });
});

describe('formatElapsed', () => {
  it('shows hours, minutes and seconds', () => {
    expect(formatElapsed(0)).toBe('0:00:00');
    expect(formatElapsed(3725)).toBe('1:02:05');
  });
});
