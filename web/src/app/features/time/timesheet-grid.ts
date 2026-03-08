import { TimeEntry } from '../../core/models';
import { addDays, diffDays } from '../../shared/dates';

export interface GridRow {
  key: string;
  projectId: number;
  projectCode: string;
  projectName: string;
  taskId: number | null;
  taskTitle: string | null;
  /** Minutes per day, Monday first. */
  days: number[];
  total: number;
  billable: boolean;
}

export interface Grid {
  dates: string[];
  rows: GridRow[];
  dayTotals: number[];
  total: number;
}

/** One row per project + task, with the week's minutes spread across the seven days. */
export function buildGrid(weekStart: string, entries: TimeEntry[]): Grid {
  const dates = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const rows = new Map<string, GridRow>();
  const dayTotals = Array(7).fill(0) as number[];
  for (const e of entries) {
    const day = diffDays(weekStart, e.date);
    if (day < 0 || day > 6) {
      continue;
    }
    const key = `${e.projectId}:${e.taskId ?? ''}`;
    let row = rows.get(key);
    if (!row) {
      row = {
        key,
        projectId: e.projectId,
        projectCode: e.projectCode,
        projectName: e.projectName,
        taskId: e.taskId ?? null,
        taskTitle: e.taskTitle ?? null,
        days: Array(7).fill(0) as number[],
        total: 0,
        billable: e.billable,
      };
      rows.set(key, row);
    }
    row.days[day] += e.minutes;
    row.total += e.minutes;
    row.billable = row.billable || e.billable;
    dayTotals[day] += e.minutes;
  }
  const sorted = [...rows.values()].sort(
    (a, b) => a.projectCode.localeCompare(b.projectCode) || (a.taskTitle ?? '').localeCompare(b.taskTitle ?? ''),
  );
  return { dates, rows: sorted, dayTotals, total: dayTotals.reduce((a, b) => a + b, 0) };
}
