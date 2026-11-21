import { Task } from '../../core/models';
import { addDays, diffDays, formatDate, isWeekend, workingDaysBetween } from '../../shared/dates';
import {
  barAt, barDates, barHandles, dragHint, dropDate, moveWorkingDays, previewDates, resizeDates, shiftedDates, snapToWorkingDay,
  dateAt,
  drawnDates,
  edgeScrollSpeed,
  linkTargets,
  stepTarget,
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

/** What the API counts as a working day, written out again here: WorkCalendar.isWorkingDay with the holidays. */
function apiWorkingDay(date: string, holidays: ReadonlySet<string>): boolean {
  return !isWeekend(date) && !holidays.has(date);
}

/** What the API does with a shift, written out again here: WorkCalendar.addWorkingDays. */
function addWorkingDays(date: string, days: number, holidays: ReadonlySet<string> = new Set()): string {
  let d = date;
  for (let left = Math.abs(days); left > 0; ) {
    d = addDays(d, days > 0 ? 1 : -1);
    if (apiWorkingDay(d, holidays)) {
      left--;
    }
  }
  return d;
}

/**
 * WorkCalendar.workingDaysBetween as the API does it: five days for every whole week, the
 * weekdays of what is left, and then the holidays on weekdays in the range taken off.
 */
function apiWorkingDaysBetween(start: string, end: string, holidays: ReadonlySet<string>): number {
  if (end < start) {
    return 0;
  }
  const days = diffDays(start, end) + 1;
  const weeks = Math.floor(days / 7);
  let count = weeks * 5;
  for (let d = addDays(start, weeks * 7); d <= end; d = addDays(d, 1)) {
    count += isWeekend(d) ? 0 : 1;
  }
  return count - [...holidays].filter((h) => h >= start && h <= end && !isWeekend(h)).length;
}

/** Rescheduler.shift for a task with both dates: the start moves, the due date keeps the working days. */
function apiShift(start: string, due: string, days: number, holidays: ReadonlySet<string> = new Set()): { from: string; to: string } {
  const length = apiWorkingDaysBetween(start, due, holidays);
  const from = addWorkingDays(start, days, holidays);
  return { from, to: addWorkingDays(from, Math.max(1, length) - 1, holidays) };
}

/** A small random generator with a seed, so that a failure can be repeated. */
function seeded(seed: number): () => number {
  let state = seed;
  return () => {
    state = (state * 1103515245 + 12345) % 2147483648;
    return state / 2147483648;
  };
}

describe('dragging across a holiday', () => {
  // Wednesday 2026-03-04 is a holiday.
  const holidays = new Set(['2026-03-04']);

  it('snaps off a holiday like off a weekend', () => {
    expect(snapToWorkingDay('2026-03-04', 1, holidays)).toBe(THU);
    expect(snapToWorkingDay('2026-03-04', -1, holidays)).toBe('2026-03-03');
    expect(dropDate(MON, 2, holidays)).toBe(THU);
  });

  it('counts and previews a move in the working days the API counts', () => {
    // Monday to Tuesday, dropped one column later: one working day, and it runs Tuesday to Thursday.
    const twoDays = task(1, MON, '2026-03-03');
    expect(moveWorkingDays(MON, 1, holidays)).toBe(1);
    expect(shiftedDates(twoDays, 1, holidays)).toEqual({ startDate: '2026-03-03', dueDate: THU });
    expect(previewDates(twoDays, 'move', 1, holidays)).toEqual({ from: '2026-03-03', to: THU });
    // Dropped on the holiday itself, it lands on Thursday: two working days.
    expect(moveWorkingDays(MON, 2, holidays)).toBe(2);
    expect(dragHint(twoDays, 'move', 2, holidays)).toBe('Move by +2 working days');
    // Without the holidays it is Monday to Friday, as before.
    expect(shiftedDates(twoDays, 1)).toEqual({ startDate: '2026-03-03', dueDate: '2026-03-04' });
  });

  it('counts working days as the server does, whole weeks and holidays included, on random ranges', () => {
    const random = seeded(7);
    for (let round = 0; round < 200; round++) {
      const holidays = new Set(Array.from({ length: 1 + Math.floor(random() * 12) }, () => addDays(MON, Math.floor(random() * 90))));
      const start = addDays(MON, Math.floor(random() * 60));
      const end = addDays(start, Math.floor(random() * 40) - 3);
      expect(workingDaysBetween(start, end, holidays), `${start} to ${end}`).toBe(apiWorkingDaysBetween(start, end, holidays));
    }
  });

  it('previews every move on a calendar with random holidays where the API puts the bar', () => {
    const random = seeded(11);
    for (let round = 0; round < 400; round++) {
      const holidays = new Set(Array.from({ length: 1 + Math.floor(random() * 8) }, () => addDays(MON, Math.floor(random() * 50))));
      const start = addDays(MON, Math.floor(random() * 30));
      const due = addDays(start, Math.floor(random() * 12));
      const columns = Math.floor(random() * 19) - 9;
      const days = moveWorkingDays(start, columns, holidays);
      const expected = days === 0 ? { from: start, to: due } : apiShift(start, due, days, holidays);
      expect(previewDates(task(1, start, due), 'move', columns, holidays), `${start} to ${due} by ${columns}`).toEqual(expected);
    }
  });

  it('never resizes an end onto a holiday', () => {
    expect(resizeDates(task(1, MON, '2026-03-03'), 'end', 1, holidays)).toEqual({ dueDate: THU });
  });
});

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

describe('drawing a link from the keyboard', () => {
  const bars = layoutGantt(
    [task(1, MON, THU, { position: 0 }), task(2, NEXT_MON, NEXT_MON, { position: 1 }), task(3, FRI, FRI, { position: 2 })],
    [],
    { zoom: 'day', critical: new Set(), collapsed: new Set(), today: MON },
  ).bars;
  const order = [...bars].sort((a, b) => a.y - b.y).map((b) => b.task.id);

  it('goes through every other bar from the top', () => {
    expect(linkTargets(bars, 2).map((b) => b.task.id)).toEqual(order.filter((id) => id !== 2));
    expect(linkTargets(bars, 1).map((b) => b.task.id)).toEqual(order.filter((id) => id !== 1));
  });

  it('steps down and up, round at the ends, and starts at the first going down or the last going up', () => {
    const targets = linkTargets(bars, 1);
    const [first, second] = targets.map((b) => b.task.id);
    expect(stepTarget(targets, null, 1)?.task.id).toBe(first);
    expect(stepTarget(targets, null, -1)?.task.id).toBe(second);
    expect(stepTarget(targets, first, 1)?.task.id).toBe(second);
    expect(stepTarget(targets, second, 1)?.task.id).toBe(first);
    expect(stepTarget(targets, first, -1)?.task.id).toBe(second);
    expect(stepTarget([], null, 1)).toBeUndefined();
  });
});

describe('scrolling near an edge while dragging', () => {
  it('does nothing in the middle and goes faster closer to an edge, leftwards as a negative number', () => {
    expect(edgeScrollSpeed(500, 0, 1000)).toBe(0);
    expect(edgeScrollSpeed(1000 - 48, 0, 1000)).toBe(0);
    expect(edgeScrollSpeed(1000 - 24, 0, 1000)).toBe(9);
    expect(edgeScrollSpeed(1000, 0, 1000)).toBe(18);
    expect(edgeScrollSpeed(1200, 0, 1000)).toBe(18);
    expect(edgeScrollSpeed(24, 0, 1000)).toBe(-9);
    expect(edgeScrollSpeed(-50, 0, 1000)).toBe(-18);
  });

  it('keeps the zone to a third of a narrow timeline, and does nothing without one', () => {
    expect(edgeScrollSpeed(50, 0, 90)).toBe(0);
    expect(edgeScrollSpeed(5, 0, 90)).toBeLessThan(0);
    expect(edgeScrollSpeed(10, 10, 10)).toBe(0);
  });
});

describe('dates for a task without them', () => {
  it('reads the day of a column', () => {
    expect(dateAt(0, MON, 28)).toBe(MON);
    expect(dateAt(27, MON, 28)).toBe(MON);
    expect(dateAt(28 * 4 + 1, MON, 28)).toBe(FRI);
  });

  it('takes the days in order and snaps both ends inwards onto working days', () => {
    expect(drawnDates(THU, MON)).toEqual({ startDate: MON, dueDate: THU });
    expect(drawnDates(SAT, '2026-03-11')).toEqual({ startDate: NEXT_MON, dueDate: '2026-03-11' });
    expect(drawnDates(THU, SUN)).toEqual({ startDate: THU, dueDate: FRI });
    expect(drawnDates(MON, '2026-03-04', new Set(['2026-03-02']))).toEqual({ startDate: '2026-03-03', dueDate: '2026-03-04' });
  });

  it('gives a drag over days off alone the first working day after them', () => {
    expect(drawnDates(SAT, SUN)).toEqual({ startDate: NEXT_MON, dueDate: NEXT_MON });
  });
});

