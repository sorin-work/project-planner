import { Phase, Task } from '../../core/models';
import { linkKey } from '../project/schedule-issues';
import { addDays } from '../../shared/dates';
import {
  BASELINE_HEIGHT, DAY_WIDTH, HEADER_HEIGHT, MONTH_LABEL_WIDTH, ROW_HEIGHT, barSpan, dateRange, dragDays, layoutGantt, linkEnds, linkPath, monthLabels, xOf,
} from './gantt-layout';

function task(id: number, startDate?: string, dueDate?: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

describe('gantt layout', () => {
  it('draws the baseline as a thin bar under the bar, inside its row', () => {
    const baseline = new Map([[1, { start: '2026-03-02', due: '2026-03-04' }]]);
    const layout = layoutGantt([task(1, '2026-03-04', '2026-03-10'), task(2, '2026-03-05', '2026-03-06')], [], {
      zoom: 'day', critical: new Set(), collapsed: new Set(), today: '2026-03-01', baseline,
    });
    const [moved, other] = layout.bars;
    // Two days earlier and three days long, from the column of 2 March.
    expect(moved.baseline).toEqual({ x: xOf('2026-03-02', layout.start, 28), y: moved.y + moved.height + 1, width: 3 * 28, height: BASELINE_HEIGHT });
    expect(moved.baseline!.y + BASELINE_HEIGHT).toBeLessThanOrEqual(HEADER_HEIGHT + ROW_HEIGHT);
    expect(other.baseline).toBeUndefined();
  });

  it('widens the range to the baseline, so a plan that slipped keeps its old bars on screen', () => {
    const tasks = [task(1, '2026-04-06', '2026-04-10')];
    const baseline = new Map([[1, { start: '2026-03-02', due: '2026-03-06' }]]);
    const layout = layoutGantt(tasks, [], { zoom: 'day', critical: new Set(), collapsed: new Set(), today: '2026-03-01', baseline });
    expect(layout.start).toBe(dateRange([task(9, '2026-03-02', '2026-03-06')]).start);
    expect(layout.bars[0].baseline!.x).toBeGreaterThanOrEqual(0);
    expect(dateRange(tasks, '2026-03-01', ['2026-05-01']).end).toBe(addDays('2026-05-01', 14));
  });

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

  it('runs an arrow from the middle of one bar end to the middle of the next bar start', () => {
    expect(linkEnds({ x: 100, y: 51, width: 56, height: 18 }, { x: 196, y: 83, width: 28, height: 18 })).toEqual({
      fromX: 156, fromY: 60, toX: 196, toY: 92,
    });
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
    expect(layout.links).toEqual([expect.objectContaining({ id: 50, successorId: 2, predecessorId: 1, critical: true })]);
    expect(layout.todayX).not.toBeNull();
    expect(layout.weekends.length).toBeGreaterThan(0);
  });

  it('shades holidays like weekends and does not count them as working days in the header', () => {
    const a = task(1, '2026-03-02', '2026-03-06');
    const plain = layoutGantt([a], [], { zoom: 'day', critical: new Set(), collapsed: new Set(), today: '2026-03-04' });
    const withHoliday = layoutGantt([a], [], {
      zoom: 'day', critical: new Set(), collapsed: new Set(), today: '2026-03-04', holidays: new Set(['2026-03-04']),
    });
    expect(withHoliday.weekends).toHaveLength(plain.weekends.length + 1);
    const x = xOf('2026-03-04', withHoliday.start, DAY_WIDTH.day);
    expect(withHoliday.weekends).toContainEqual({ x, width: DAY_WIDTH.day });
    expect(withHoliday.ticks.find((t) => t.x === x)!.major).toBe(false);
  });

  it('marks a link as broken only when the issue list names it', () => {
    // Task 2 starts before task 1 is due. The layout does not judge that itself.
    const a = task(1, '2026-03-02', '2026-03-06');
    const b = task(2, '2026-03-04', '2026-03-05', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }] });
    const c = task(3, '2026-03-09', '2026-03-10', { predecessors: [{ id: 51, predecessorId: 1, successorId: 3, lagDays: 0 }] });
    const options = { zoom: 'day' as const, critical: new Set([1, 2]), collapsed: new Set<number>(), today: '2026-03-04' };

    expect(layoutGantt([a, b, c], [], options).links.map((l) => l.broken)).toEqual([false, false]);
    const layout = layoutGantt([a, b, c], [], { ...options, broken: new Set([linkKey(1, 2)]) });
    expect(layout.links).toEqual([
      expect.objectContaining({ id: 50, broken: true, critical: true }),
      expect.objectContaining({ id: 51, broken: false }),
    ]);
  });

  it('labels the month the chart begins in and every month that begins in it', () => {
    // Monday 24 May 2027: eight days of May at day zoom are plenty for its label.
    expect(monthLabels('2027-05-24', 50, DAY_WIDTH.day).map((m) => `${m.label} at ${m.x}`)).toEqual([
      'May 2027 at 0', 'Jun 2027 at 224', 'Jul 2027 at 1064',
    ]);
    expect(monthLabels('2027-06-01', 20, DAY_WIDTH.week).map((m) => m.label)).toEqual(['Jun 2027']);
  });

  it('leaves out the label of a first month that is too short to hold it', () => {
    // The chart begins on Monday 31 May: "May 2027" would be printed over "Jun 2027" one column on.
    expect(monthLabels('2027-05-31', 50, DAY_WIDTH.week).map((m) => `${m.label} at ${m.x}`)).toEqual([
      'Jun 2027 at 12', 'Jul 2027 at 372',
    ]);
    expect(monthLabels('2027-05-31', 50, DAY_WIDTH.day)[0]).toEqual({ x: 28, label: 'Jun 2027', major: true });
    // At month zoom a day is four pixels: two weeks are not enough, three are.
    expect(monthLabels('2027-05-17', 90, DAY_WIDTH.month)[0].label).toBe('Jun 2027');
    expect(monthLabels('2027-05-10', 90, DAY_WIDTH.month)[0].label).toBe('May 2027');
  });

  it('never prints two month labels into each other, whatever day the chart begins on', () => {
    for (const dayWidth of Object.values(DAY_WIDTH)) {
      for (let offset = 0; offset < 70; offset += 7) {
        const labels = monthLabels(addDays('2027-05-03', offset), 200, dayWidth);
        expect(labels.length).toBeGreaterThan(3);
        for (let i = 1; i < labels.length; i++) {
          expect(labels[i].x - labels[i - 1].x, `${labels[i - 1].label} at ${dayWidth} px`).toBeGreaterThanOrEqual(MONTH_LABEL_WIDTH);
        }
      }
    }
  });

  it('hides the tasks of collapsed phases', () => {
    const phases: Phase[] = [{ id: 7, projectId: 1, name: 'Build', position: 0 }];
    const layout = layoutGantt([task(1, '2026-03-02', '2026-03-03', { phaseId: 7 })], phases, {
      zoom: 'week', critical: new Set(), collapsed: new Set([7]), today: '2026-03-04',
    });
    expect(layout.rows.map((r) => r.kind)).toEqual(['phase']);
    expect(layout.bars).toHaveLength(0);
  });

  it('shows parent progress from finished subtasks', () => {
    const parent = task(1, '2026-03-02', '2026-03-06');
    const subs = [
      task(2, '2026-03-02', '2026-03-03', { parentId: 1, status: 'DONE' }),
      task(3, '2026-03-04', '2026-03-06', { parentId: 1 }),
    ];
    const layout = layoutGantt([parent, ...subs], [], { zoom: 'day', critical: new Set(), collapsed: new Set(), today: '2026-03-04' });
    expect(layout.bars.find((b) => b.task.id === 1)!.progress).toBe(50);
    expect(layout.rows.filter((r) => r.depth === 1)).toHaveLength(2);
  });
});
