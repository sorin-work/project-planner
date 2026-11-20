import { Task } from '../../core/models';
import { Holidays, NO_HOLIDAYS, addDays, addWorkingDays, formatDate, isWorkingDay, workingDaysBetween } from '../../shared/dates';
import { BarBox, GanttBar, MILESTONE_RADIUS, ROW_HEIGHT } from './gantt-layout';

// What a drag on the timeline means in dates. The component only measures pixels
// and hands them over as whole day columns (dragDays); everything else is here.
// Working days are counted with the organisation's holidays, as the API counts them.

/** What is being dragged: the whole bar, one of its ends, or the connector that draws a link. */
export type DragMode = 'move' | 'start' | 'end' | 'link';

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Widest a resize handle gets. On a narrow bar the handles shrink so that a third stays for moving. */
export const HANDLE_WIDTH = 6;
/**
 * The link connector is a dot just past the right end of its bar. It overlaps the
 * end by half a pixel, so the pointer can go from the bar to the dot without
 * leaving the bar's hover area in between.
 */
export const CONNECTOR_RADIUS = 4.5;
const CONNECTOR_GAP = 4;
/** How far outside a bar, left or right, a link may be dropped and still count. */
const DROP_MARGIN = 4;

type Dates = Pick<Task, 'startDate' | 'dueDate'>;

/** The first and last day of a task's bar. A task with one date is a one-day bar on it. */
export function barDates(task: Dates): { from: string; to: string } | null {
  const from = task.startDate ?? task.dueDate;
  const to = task.dueDate ?? task.startDate;
  return from && to ? { from, to } : null;
}

/** {@code date} itself on a working day; from a weekend or a holiday, the next working day in the direction of the drag. */
export function snapToWorkingDay(date: string, direction: number, holidays: Holidays = NO_HOLIDAYS): string {
  let day = date;
  while (!isWorkingDay(day, holidays)) {
    day = addDays(day, direction < 0 ? -1 : 1);
  }
  return day;
}

/** Where the first day of a bar lands when it is dropped {@code calendarDays} columns away. */
export function dropDate(from: string, calendarDays: number, holidays: Holidays = NO_HOLIDAYS): string {
  return calendarDays === 0 ? from : snapToWorkingDay(addDays(from, calendarDays), calendarDays, holidays);
}

/**
 * The working days to shift a bar by so that its first day lands where it was
 * dropped. Counted from the old first day: the days after it up to the drop day
 * when moving later, the days from the drop day up to it when moving earlier.
 * That is what the API adds, so the bar ends up on the drop day even when it
 * started on a weekend.
 */
export function moveWorkingDays(from: string, calendarDays: number, holidays: Holidays = NO_HOLIDAYS): number {
  if (calendarDays === 0) {
    return 0;
  }
  const target = dropDate(from, calendarDays, holidays);
  return calendarDays > 0
    ? workingDaysBetween(addDays(from, 1), target, holidays)
    : -workingDaysBetween(target, addDays(from, -1), holidays);
}

/**
 * The task's dates after the API has shifted it by {@code workingDays}: the start
 * moves that many working days and the due date keeps the length in working days,
 * so a Monday to Friday task moved by one runs Tuesday to Monday. A task with one
 * date moves that date. This is Rescheduler.shift in the API, repeated here so
 * that a bar can be drawn where it will land.
 */
export function shiftedDates(task: Dates, workingDays: number, holidays: Holidays = NO_HOLIDAYS): Dates {
  if (workingDays === 0) {
    return { startDate: task.startDate, dueDate: task.dueDate };
  }
  if (task.startDate && task.dueDate) {
    const length = Math.max(1, workingDaysBetween(task.startDate, task.dueDate, holidays));
    const startDate = addWorkingDays(task.startDate, workingDays, holidays);
    return { startDate, dueDate: addWorkingDays(startDate, length - 1, holidays) };
  }
  return {
    startDate: task.startDate ? addWorkingDays(task.startDate, workingDays, holidays) : undefined,
    dueDate: task.dueDate ? addWorkingDays(task.dueDate, workingDays, holidays) : undefined,
  };
}

/**
 * The date to save after dragging one end of a bar by {@code calendarDays}
 * columns: a new start or a new due date, never past the other one. Null when
 * the end did not move.
 *
 * A task with one date is a one-day bar. Its end that stands for the date it has
 * moves freely, there is nothing to run into. The other end gives the task the
 * date it lacks, and only away from the one it has.
 */
export function resizeDates(
  task: Dates,
  edge: 'start' | 'end',
  calendarDays: number,
  holidays: Holidays = NO_HOLIDAYS,
): { startDate: string } | { dueDate: string } | null {
  const dates = barDates(task);
  if (!dates || calendarDays === 0) {
    return null;
  }
  if (edge === 'start') {
    const start = dropDate(dates.from, calendarDays, holidays);
    const startDate = task.dueDate && start > task.dueDate ? task.dueDate : start;
    return startDate === dates.from ? null : { startDate };
  }
  const due = dropDate(dates.to, calendarDays, holidays);
  const dueDate = task.startDate && due < task.startDate ? task.startDate : due;
  return dueDate === dates.to ? null : { dueDate };
}

/**
 * The days a bar covers while it is being dragged. It is drawn exactly where the
 * drop would put it: a moved bar with the dates the API will answer, a resized
 * one with the date that would be saved.
 */
export function previewDates(
  task: Dates,
  mode: Exclude<DragMode, 'link'>,
  calendarDays: number,
  holidays: Holidays = NO_HOLIDAYS,
): { from: string; to: string } | null {
  const dates = barDates(task);
  if (!dates) {
    return null;
  }
  if (mode === 'move') {
    return barDates(shiftedDates(task, moveWorkingDays(dates.from, calendarDays, holidays), holidays));
  }
  const resized = resizeDates(task, mode, calendarDays, holidays);
  return resized ? barDates({ startDate: task.startDate, dueDate: task.dueDate, ...resized }) : dates;
}

/** What dropping the bar now would do, in words. Null while nothing would change. */
export function dragHint(
  task: Dates,
  mode: Exclude<DragMode, 'link'>,
  calendarDays: number,
  holidays: Holidays = NO_HOLIDAYS,
): string | null {
  const dates = barDates(task);
  if (!dates) {
    return null;
  }
  if (mode === 'move') {
    const days = moveWorkingDays(dates.from, calendarDays, holidays);
    return days === 0 ? null : `Move by ${days > 0 ? '+' : ''}${days} working day${Math.abs(days) === 1 ? '' : 's'}`;
  }
  const resized = resizeDates(task, mode, calendarDays, holidays);
  if (!resized) {
    return null;
  }
  return 'startDate' in resized ? `Start ${formatDate(resized.startDate)}` : `Due ${formatDate(resized.dueDate)}`;
}

/**
 * Where to grab a bar: a strip at each end to resize it and a dot after it to draw
 * a link from. A milestone is a diamond in the middle of its day, so its dot sits
 * at the diamond's tip. (Milestones have no resize strips; the component leaves them out.)
 */
export function barHandles(bar: BarBox & { milestone?: boolean }): { start: Rect; end: Rect; connector: { x: number; y: number } } {
  const width = Math.min(HANDLE_WIDTH, Math.floor(bar.width / 3));
  const right = bar.milestone ? bar.x + bar.width / 2 + MILESTONE_RADIUS : bar.x + bar.width - 1;
  return {
    start: { x: bar.x, y: bar.y, width, height: bar.height },
    end: { x: bar.x + bar.width - width, y: bar.y, width, height: bar.height },
    connector: { x: right + CONNECTOR_GAP, y: bar.y + bar.height / 2 },
  };
}

/** The bar a link was dropped on: anywhere in its row between its two ends, with a little margin. */
export function barAt(bars: GanttBar[], x: number, y: number): GanttBar | undefined {
  return bars.find((bar) => {
    const top = bar.y - (ROW_HEIGHT - bar.height) / 2;
    return x >= bar.x - DROP_MARGIN && x <= bar.x + bar.width + DROP_MARGIN && y >= top && y < top + ROW_HEIGHT;
  });
}
