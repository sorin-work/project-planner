import { Task } from '../../core/models';
import { addDays, formatDate, isWeekend } from '../../shared/dates';
import { barAt, barDates, barHandles, dragHint, dropDate, moveWorkingDays, previewDates, resizeDates, snapToWorkingDay } from './gantt-edit';
import { HEADER_HEIGHT, ROW_HEIGHT, layoutGantt } from './gantt-layout';

// 2026-03-02 is a Monday.
const MON = '2026-03-02';
const THU = '2026-03-05';
const FRI = '2026-03-06';
const SAT = '2026-03-07';
const SUN = '2026-03-08';
const NEXT_MON = '2026-03-09';

function task(id: number, startDate?: string, dueDate?: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

/** What the API does with a shift: WorkCalendar.addWorkingDays. */
function addWorkingDays(date: string, days: number): string {
  let d = date;
  for (let left = Math.abs(days); left > 0; ) {
    d = addDays(d, days > 0 ? 1 : -1);
    if (!isWeekend(d)) {
      left--;
    }
  }
  return d;
}

describe('dragging on the timeline', () => {
  it('snaps a weekend to the next working day in the direction of the drag', () => {
    expect(snapToWorkingDay(SAT, 1)).toBe(NEXT_MON);
    expect(snapToWorkingDay(SUN, 1)).toBe(NEXT_MON);
    expect(snapToWorkingDay(SAT, -1)).toBe(FRI);
    expect(snapToWorkingDay(SUN, -1)).toBe(FRI);
    expect(snapToWorkingDay(FRI, 1)).toBe(FRI);
    expect(snapToWorkingDay(FRI, -1)).toBe(FRI);
  });

  it('drops a bar on the day under it, or past the weekend', () => {
    expect(dropDate(THU, 1)).toBe(FRI);
    expect(dropDate(THU, 2)).toBe(NEXT_MON);
    expect(dropDate(NEXT_MON, -1)).toBe(FRI);
    expect(dropDate(SAT, 0)).toBe(SAT);
  });

  it('counts a move in working days from the old start', () => {
    expect(moveWorkingDays(THU, 1)).toBe(1);
    expect(moveWorkingDays(THU, 2)).toBe(2); // dropped on Saturday, lands on Monday
    expect(moveWorkingDays(THU, 4)).toBe(2);
    expect(moveWorkingDays(THU, 7)).toBe(5);
    expect(moveWorkingDays(THU, -3)).toBe(-3);
    expect(moveWorkingDays(THU, 0)).toBe(0);
  });

  it('moves a Monday bar left onto Friday instead of leaving it where it was', () => {
    expect(moveWorkingDays(NEXT_MON, -1)).toBe(-1);
    expect(moveWorkingDays(NEXT_MON, -2)).toBe(-1);
    expect(moveWorkingDays(NEXT_MON, -3)).toBe(-1);
    expect(moveWorkingDays(NEXT_MON, -4)).toBe(-2);
  });

  it('asks the API for exactly the shift that lands on the drop day, weekend starts included', () => {
    for (let offset = 0; offset < 14; offset++) {
      const from = addDays(MON, offset);
      for (let columns = -9; columns <= 9; columns++) {
        if (columns === 0) {
          continue;
        }
        const days = moveWorkingDays(from, columns);
        expect(days, `${from} by ${columns}`).not.toBe(0);
        expect(Math.sign(days)).toBe(Math.sign(columns));
        expect(addWorkingDays(from, days), `${from} by ${columns}`).toBe(dropDate(from, columns));
      }
    }
  });

  it('resizes the end of a bar and never lets it pass the start', () => {
    const t = task(1, MON, THU);
    expect(resizeDates(t, 'end', 1)).toEqual({ dueDate: FRI });
    expect(resizeDates(t, 'end', 2)).toEqual({ dueDate: NEXT_MON }); // Saturday, dragging right
    expect(resizeDates(t, 'end', -1)).toEqual({ dueDate: '2026-03-04' });
    expect(resizeDates(t, 'end', -3)).toEqual({ dueDate: MON });
    expect(resizeDates(t, 'end', -10)).toEqual({ dueDate: MON });
    expect(resizeDates(task(1, MON, NEXT_MON), 'end', -1)).toEqual({ dueDate: FRI }); // Sunday, dragging left
  });

  it('resizes the start of a bar and never lets it pass the due date', () => {
    const t = task(1, MON, THU);
    expect(resizeDates(t, 'start', 1)).toEqual({ startDate: '2026-03-03' });
    expect(resizeDates(t, 'start', -1)).toEqual({ startDate: '2026-02-27' }); // Sunday, dragging left: Friday
    expect(resizeDates(t, 'start', 3)).toEqual({ startDate: THU });
    expect(resizeDates(t, 'start', 5)).toEqual({ startDate: THU }); // Saturday would snap to Monday, past the due date
  });

  it('has nothing to save when the end did not move', () => {
    expect(resizeDates(task(1, MON, THU), 'end', 0)).toBeNull();
    expect(resizeDates(task(1, MON, MON), 'end', -2)).toBeNull();
    expect(resizeDates(task(1, MON, MON), 'start', 2)).toBeNull();
    expect(resizeDates(task(1), 'end', 2)).toBeNull();
  });

  it('gives a task with one date the other one, without inventing it by accident', () => {
    const dueOnly = task(1, undefined, THU);
    expect(barDates(dueOnly)).toEqual({ from: THU, to: THU });
    expect(resizeDates(dueOnly, 'start', -2)).toEqual({ startDate: '2026-03-03' });
    expect(resizeDates(dueOnly, 'start', 1)).toBeNull();
    expect(resizeDates(dueOnly, 'end', 1)).toEqual({ dueDate: FRI });
    expect(resizeDates(task(1, THU), 'end', 2)).toEqual({ dueDate: NEXT_MON });
    expect(resizeDates(task(1, THU), 'end', -1)).toBeNull();
  });

  it('previews the bar where it would land', () => {
    const t = task(1, THU, FRI);
    expect(previewDates(t, 'move', 2)).toEqual({ from: NEXT_MON, to: '2026-03-10' });
    expect(previewDates(t, 'move', 0)).toEqual({ from: THU, to: FRI });
    expect(previewDates(t, 'end', 1)).toEqual({ from: THU, to: NEXT_MON });
    expect(previewDates(t, 'start', -1)).toEqual({ from: '2026-03-04', to: FRI });
    expect(previewDates(t, 'start', 4)).toEqual({ from: FRI, to: FRI });
    expect(previewDates(task(1), 'move', 2)).toBeNull();
  });

  it('says what a drop would do', () => {
    const t = task(1, THU, FRI);
    expect(dragHint(t, 'move', 2)).toBe('Move by +2 working days');
    expect(dragHint(t, 'move', -1)).toBe('Move by -1 working day');
    expect(dragHint(t, 'move', 0)).toBeNull();
    expect(dragHint(t, 'end', 1)).toBe(`Due ${formatDate(NEXT_MON)}`);
    expect(dragHint(t, 'start', -1)).toBe(`Start ${formatDate('2026-03-04')}`);
    expect(dragHint(t, 'start', 3)).toBe(`Start ${formatDate(FRI)}`);
    expect(dragHint(t, 'end', -1)).toBe(`Due ${formatDate(THU)}`);
    expect(dragHint(task(1, THU, THU), 'end', -1)).toBeNull();
  });

  it('puts a resize strip at each end and the link dot just past the bar', () => {
    const grab = barHandles({ x: 100, y: 51, width: 84, height: 18 });
    expect(grab.start).toEqual({ x: 100, y: 51, width: 6, height: 18 });
    expect(grab.end).toEqual({ x: 178, y: 51, width: 6, height: 18 });
    expect(grab.connector).toEqual({ x: 187, y: 60 });
  });

  it('leaves the middle of a narrow bar for moving it', () => {
    // One day at week zoom is twelve pixels.
    const grab = barHandles({ x: 100, y: 51, width: 12, height: 18 });
    expect(grab.start).toEqual({ x: 100, y: 51, width: 4, height: 18 });
    expect(grab.end).toEqual({ x: 108, y: 51, width: 4, height: 18 });
  });

  it('puts the dot of a milestone at the tip of its diamond', () => {
    expect(barHandles({ x: 100, y: 51, width: 28, height: 18, milestone: true }).connector).toEqual({ x: 126, y: 60 });
  });

  it('finds the bar a link was dropped on by its row and its ends', () => {
    const layout = layoutGantt([task(1, MON, THU), task(2, NEXT_MON, '2026-03-10')], [], {
      zoom: 'day', critical: new Set(), collapsed: new Set(), today: MON,
    });
    const [a, b] = layout.bars;
    // Anywhere in the row counts, not only the bar's own eighteen pixels.
    expect(barAt(layout.bars, a.x + 10, HEADER_HEIGHT + 1)?.task.id).toBe(1);
    expect(barAt(layout.bars, a.x + 10, HEADER_HEIGHT + ROW_HEIGHT - 1)?.task.id).toBe(1);
    expect(barAt(layout.bars, b.x + 10, HEADER_HEIGHT + ROW_HEIGHT)?.task.id).toBe(2);
    expect(barAt(layout.bars, b.x - 4, b.y + 5)?.task.id).toBe(2);
    expect(barAt(layout.bars, b.x - 5, b.y + 5)).toBeUndefined();
    expect(barAt(layout.bars, a.x + a.width + 5, a.y + 5)).toBeUndefined();
    expect(barAt(layout.bars, a.x + 10, HEADER_HEIGHT - 1)).toBeUndefined();
    expect(barAt(layout.bars, b.x + 10, HEADER_HEIGHT + 2 * ROW_HEIGHT)).toBeUndefined();
  });
});
