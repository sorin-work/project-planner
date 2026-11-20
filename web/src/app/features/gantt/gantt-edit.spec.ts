import { Task } from '../../core/models';
import { addDays, formatDate, isWeekend } from '../../shared/dates';
import {
  barAt, barDates, barHandles, dragHint, dropDate, moveWorkingDays, previewDates, resizeDates, shiftedDates, snapToWorkingDay,
} from './gantt-edit';
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

/** What the API does with a shift, written out again here: WorkCalendar.addWorkingDays. */
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

/** Rescheduler.shift for a task with both dates: the start moves, the due date keeps the working days. */
function apiShift(start: string, due: string, days: number): { from: string; to: string } {
  let length = 0;
  for (let d = start; d <= due; d = addDays(d, 1)) {
    length += isWeekend(d) ? 0 : 1;
  }
  const from = addWorkingDays(start, days);
  return { from, to: addWorkingDays(from, Math.max(1, length) - 1) };
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

  it('moves the one date a task has freely, in both directions', () => {
    const dueOnly = task(1, undefined, THU);
    expect(barDates(dueOnly)).toEqual({ from: THU, to: THU });
    // There is no start to run into: the due date can come forward as well.
    expect(resizeDates(dueOnly, 'end', 1)).toEqual({ dueDate: FRI });
    expect(resizeDates(dueOnly, 'end', -1)).toEqual({ dueDate: '2026-03-04' });
    expect(resizeDates(dueOnly, 'end', -4)).toEqual({ dueDate: '2026-02-27' }); // Sunday, dragging left: Friday
    const startOnly = task(1, THU);
    expect(resizeDates(startOnly, 'start', -1)).toEqual({ startDate: '2026-03-04' });
    expect(resizeDates(startOnly, 'start', 1)).toEqual({ startDate: FRI });
    expect(resizeDates(startOnly, 'start', 2)).toEqual({ startDate: NEXT_MON });
  });

  it('gives a task with one date the other one, but only away from the one it has', () => {
    const dueOnly = task(1, undefined, THU);
    expect(resizeDates(dueOnly, 'start', -2)).toEqual({ startDate: '2026-03-03' });
    expect(resizeDates(dueOnly, 'start', 1)).toBeNull();
    const startOnly = task(1, THU);
    expect(resizeDates(startOnly, 'end', 2)).toEqual({ dueDate: NEXT_MON });
    expect(resizeDates(startOnly, 'end', -1)).toBeNull();
  });

  it('shifts a task the way the API will: the start moves and the working days stay', () => {
    expect(shiftedDates(task(1, MON, FRI), 1)).toEqual({ startDate: '2026-03-03', dueDate: NEXT_MON });
    expect(shiftedDates(task(1, MON, FRI), -1)).toEqual({ startDate: '2026-02-27', dueDate: THU });
    expect(shiftedDates(task(1, THU, FRI), 0)).toEqual({ startDate: THU, dueDate: FRI });
    expect(shiftedDates(task(1, THU), 2)).toEqual({ startDate: NEXT_MON, dueDate: undefined });
    expect(shiftedDates(task(1, undefined, NEXT_MON), -1)).toEqual({ startDate: undefined, dueDate: FRI });
    // Thursday to Saturday is two working days and stays two.
    expect(shiftedDates(task(1, THU, SAT), 1)).toEqual({ startDate: FRI, dueDate: NEXT_MON });
  });

  it('previews a moved bar with the length it will have, not the one it had on screen', () => {
    // Monday to Friday, one column to the right: Tuesday to Monday. Seven columns wide instead of five.
    expect(previewDates(task(1, MON, FRI), 'move', 1)).toEqual({ from: '2026-03-03', to: NEXT_MON });
    // The other way: dropped on Sunday it goes to the Friday before and ends on Thursday.
    expect(previewDates(task(1, MON, FRI), 'move', -1)).toEqual({ from: '2026-02-27', to: THU });
    // A bar that spans a weekend closes up when it moves clear of it, and opens again a week on.
    expect(previewDates(task(1, FRI, NEXT_MON), 'move', 3)).toEqual({ from: NEXT_MON, to: '2026-03-10' });
    expect(previewDates(task(1, FRI, NEXT_MON), 'move', 7)).toEqual({ from: '2026-03-13', to: '2026-03-16' });
    expect(previewDates(task(1, FRI, NEXT_MON), 'move', -1)).toEqual({ from: THU, to: FRI });
    expect(previewDates(task(1, THU, FRI), 'move', 0)).toEqual({ from: THU, to: FRI });
    expect(previewDates(task(1, undefined, FRI), 'move', 1)).toEqual({ from: NEXT_MON, to: NEXT_MON });
    expect(previewDates(task(1), 'move', 2)).toBeNull();
  });

  it('previews every move exactly where the API puts the bar', () => {
    for (let offset = 0; offset < 14; offset++) {
      const start = addDays(MON, offset);
      for (let span = 0; span < 12; span++) {
        const due = addDays(start, span);
        for (let columns = -9; columns <= 9; columns++) {
          const days = moveWorkingDays(start, columns);
          const expected = days === 0 ? { from: start, to: due } : apiShift(start, due, days);
          expect(previewDates(task(1, start, due), 'move', columns), `${start} to ${due} by ${columns}`).toEqual(expected);
        }
      }
    }
  });

  it('previews a resized bar with the date that would be saved', () => {
    const t = task(1, THU, FRI);
    expect(previewDates(t, 'end', 1)).toEqual({ from: THU, to: NEXT_MON });
    expect(previewDates(t, 'start', -1)).toEqual({ from: '2026-03-04', to: FRI });
    expect(previewDates(t, 'start', 4)).toEqual({ from: FRI, to: FRI });
    expect(previewDates(t, 'end', -9)).toEqual({ from: THU, to: THU });
    // A due date on its own moves as a one-day bar, a new start stretches it.
    expect(previewDates(task(1, undefined, THU), 'end', -1)).toEqual({ from: '2026-03-04', to: '2026-03-04' });
    expect(previewDates(task(1, undefined, THU), 'start', -2)).toEqual({ from: '2026-03-03', to: THU });
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
