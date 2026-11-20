import { Phase, Task } from '../../core/models';
import { Holidays, NO_HOLIDAYS, addDays, diffDays, isWorkingDay, maxDate, minDate, monthName, toDate, today, weekStart } from '../../shared/dates';
import { linkKey } from '../project/schedule-issues';
import { groupTasks } from '../project/task-groups';

export type Zoom = 'day' | 'week' | 'month';

export const DAY_WIDTH: Record<Zoom, number> = { day: 28, week: 12, month: 4 };
export const ROW_HEIGHT = 32;
export const HEADER_HEIGHT = 44;
const BAR_HEIGHT = 18;
/** The baseline's thin bar under a bar: between the bar's lower edge and the row line. */
export const BASELINE_HEIGHT = 4;
const BASELINE_GAP = 1;
/** Half the width of a milestone's diamond. */
export const MILESTONE_RADIUS = 8;

export interface GanttRow {
  kind: 'phase' | 'task';
  y: number;
  label: string;
  depth: number;
  task?: Task;
  phaseId?: number | null;
}

export interface GanttBar {
  task: Task;
  x: number;
  y: number;
  width: number;
  height: number;
  milestone: boolean;
  critical: boolean;
  done: boolean;
  /** 0-100, from the task's status (subtask share for parents). */
  progress: number;
  /** Where the task was in the baseline being compared with, drawn as a thin bar under this one. */
  baseline?: { x: number; y: number; width: number; height: number };
}

export interface GanttLink {
  id: number;
  /** The task that waits, and the task it waits for. */
  successorId: number;
  predecessorId: number;
  path: string;
  critical: boolean;
  /** The API reported this dependency: it does not hold, or it is not enforced. */
  broken: boolean;
}

export interface GanttTick {
  x: number;
  label: string;
  major: boolean;
}

export interface GanttLayout {
  start: string;
  end: string;
  dayWidth: number;
  width: number;
  height: number;
  rows: GanttRow[];
  bars: GanttBar[];
  links: GanttLink[];
  ticks: GanttTick[];
  months: GanttTick[];
  weekends: { x: number; width: number }[];
  todayX: number | null;
}

/** The room a month label takes in the header: "Sep 2027" in bold 11 pixel type, with its padding. */
export const MONTH_LABEL_WIDTH = 64;

/**
 * The month labels of the header: one where each month begins, and one at the left
 * edge for the month the chart begins in. That first month can be a single day (the
 * chart begins on a Monday, which may be the 31st), and its label would then be
 * printed over the next one. So it is left out when the next month begins before the
 * label ends. Every month after it is whole and wide enough at every zoom.
 */
export function monthLabels(start: string, days: number, dayWidth: number): GanttTick[] {
  const months: GanttTick[] = [];
  for (let i = 0; i < days; i++) {
    const date = addDays(start, i);
    if (i === 0 || toDate(date).getUTCDate() === 1) {
      months.push({ x: i * dayWidth, label: `${monthName(date)} ${date.slice(0, 4)}`, major: true });
    }
  }
  if (months.length > 1 && months[1].x - months[0].x < MONTH_LABEL_WIDTH) {
    months.shift();
  }
  return months;
}

/**
 * Visible date range: the plan plus some breathing room, starting on a Monday. Any
 * {@code extra} dates are in it too: those of a baseline, whose bars would otherwise
 * fall off the left edge when the plan has slipped.
 */
export function dateRange(tasks: Task[], reference: string = today(), extra: string[] = []): { start: string; end: string } {
  const dates = [...tasks.flatMap((t) => [t.startDate, t.dueDate]), ...extra];
  const first = minDate(dates) ?? reference;
  const last = maxDate(dates) ?? addDays(reference, 28);
  const start = weekStart(addDays(first, -3));
  const end = addDays(last, 14);
  return { start, end };
}

/** Left edge of a day column. */
export function xOf(date: string, start: string, dayWidth: number): number {
  return diffDays(start, date) * dayWidth;
}

/** A bar covers its start and due day: a one-day task is one column wide. */
export function barSpan(startDate: string, dueDate: string, rangeStart: string, dayWidth: number): { x: number; width: number } {
  const x = xOf(startDate, rangeStart, dayWidth);
  const width = (diffDays(startDate, dueDate) + 1) * dayWidth;
  return { x, width };
}

/** The part of a bar that the arithmetic around it needs. */
export type BarBox = Pick<GanttBar, 'x' | 'y' | 'width' | 'height'>;

/** An arrow leaves the middle of a bar's right end and arrives at the middle of the other bar's left end. */
export function linkEnds(from: BarBox, to: BarBox): { fromX: number; fromY: number; toX: number; toY: number } {
  return { fromX: from.x + from.width, fromY: from.y + from.height / 2, toX: to.x, toY: to.y + to.height / 2 };
}

/**
 * An elbow connector from the end of one bar to the start of another. When the
 * successor starts before the predecessor ends the line drops between the rows
 * and doubles back.
 */
export function linkPath(fromX: number, fromY: number, toX: number, toY: number): string {
  const gap = 8;
  if (toX >= fromX + gap * 2) {
    const midX = fromX + gap;
    return `M ${fromX} ${fromY} H ${midX} V ${toY} H ${toX}`;
  }
  const between = fromY + (toY > fromY ? ROW_HEIGHT / 2 : -ROW_HEIGHT / 2);
  return `M ${fromX} ${fromY} H ${fromX + gap} V ${between} H ${toX - gap} V ${toY} H ${toX}`;
}

export function layoutGantt(
  tasks: Task[],
  phases: Phase[],
  options: {
    zoom: Zoom;
    critical: Set<number>;
    collapsed: Set<number>;
    today?: string;
    broken?: Set<string>;
    /** Baseline dates by task, from the API's comparison. A task without an entry has no thin bar. */
    baseline?: Map<number, { start: string; due: string }>;
    holidays?: Holidays;
  },
): GanttLayout {
  const dayWidth = DAY_WIDTH[options.zoom];
  const now = options.today ?? today();
  const baseline = options.baseline ?? new Map<number, { start: string; due: string }>();
  const { start, end } = dateRange(tasks, now, [...baseline.values()].flatMap((d) => [d.start, d.due]));
  const days = diffDays(start, end) + 1;

  const rows: GanttRow[] = [];
  const bars: GanttBar[] = [];
  const rowOf = new Map<number, number>();
  let y = HEADER_HEIGHT;

  const subtaskCounts = new Map<number, { total: number; done: number }>();
  for (const t of tasks) {
    if (t.parentId) {
      const c = subtaskCounts.get(t.parentId) ?? { total: 0, done: 0 };
      c.total++;
      if (t.status === 'DONE') {
        c.done++;
      }
      subtaskCounts.set(t.parentId, c);
    }
  }

  for (const group of groupTasks(tasks, phases)) {
    if (group.phase || phases.length) {
      rows.push({ kind: 'phase', y, label: group.phase?.name ?? 'No phase', depth: 0, phaseId: group.phase?.id ?? null });
      y += ROW_HEIGHT;
    }
    const collapsed = options.collapsed.has(group.phase?.id ?? 0);
    if (collapsed) {
      continue;
    }
    for (const { task, depth } of group.rows) {
      rows.push({ kind: 'task', y, label: task.title, depth, task });
      rowOf.set(task.id, y);
      const from = task.startDate ?? task.dueDate;
      const to = task.dueDate ?? task.startDate;
      const then = baseline.get(task.id);
      const barY = y + (ROW_HEIGHT - BAR_HEIGHT) / 2;
      const thin = then
        ? { ...barSpan(then.start, then.due, start, dayWidth), y: barY + BAR_HEIGHT + BASELINE_GAP, height: BASELINE_HEIGHT }
        : undefined;
      if (from && to) {
        const span = barSpan(from, to, start, dayWidth);
        const counts = subtaskCounts.get(task.id);
        const progress =
          task.status === 'DONE' ? 100 : counts ? (counts.done / counts.total) * 100 : task.status === 'IN_REVIEW' ? 80 : 0;
        bars.push({
          task,
          x: span.x,
          y: barY,
          width: task.milestone ? dayWidth : span.width,
          height: BAR_HEIGHT,
          milestone: task.milestone,
          critical: options.critical.has(task.id),
          done: task.status === 'DONE',
          progress,
          baseline: thin,
        });
      }
      y += ROW_HEIGHT;
    }
  }

  const barOf = new Map(bars.map((b) => [b.task.id, b]));
  const links: GanttLink[] = [];
  for (const task of tasks) {
    for (const dep of task.predecessors) {
      const from = barOf.get(dep.predecessorId);
      const to = barOf.get(task.id);
      if (!from || !to) {
        continue;
      }
      const ends = linkEnds(from, to);
      links.push({
        id: dep.id,
        successorId: task.id,
        predecessorId: dep.predecessorId,
        path: linkPath(ends.fromX, ends.fromY, ends.toX, ends.toY),
        critical: from.critical && to.critical,
        // Taken from the issue list, so the timeline never decides this with its own date maths.
        broken: options.broken?.has(linkKey(dep.predecessorId, task.id)) ?? false,
      });
    }
  }

  const holidays = options.holidays ?? NO_HOLIDAYS;
  const ticks: GanttTick[] = [];
  const months = monthLabels(start, days, dayWidth);
  const weekends: { x: number; width: number }[] = [];
  for (let i = 0; i < days; i++) {
    const date = addDays(start, i);
    const x = i * dayWidth;
    if (options.zoom === 'day') {
      ticks.push({ x, label: String(toDate(date).getUTCDate()), major: isWorkingDay(date, holidays) });
    } else if (options.zoom === 'week' && i % 7 === 0) {
      ticks.push({ x, label: String(toDate(date).getUTCDate()), major: true });
    }
    // Holidays are shaded like weekends: nobody works on them.
    if (!isWorkingDay(date, holidays) && options.zoom !== 'month') {
      weekends.push({ x, width: dayWidth });
    }
  }

  const todayX = now >= start && now <= end ? xOf(now, start, dayWidth) + dayWidth / 2 : null;
  return { start, end, dayWidth, width: days * dayWidth, height: y, rows, bars, links, ticks, months, weekends, todayX };
}

/** Whole days a drag of {@code dx} pixels moves a bar. */
export function dragDays(dx: number, dayWidth: number): number {
  return Math.round(dx / dayWidth);
}
