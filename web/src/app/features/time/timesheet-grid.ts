import { TimeEntry, Week } from '../../core/models';
import { addDays, dayNameLong, diffDays, parseIsoDate, weekStart } from '../../shared/dates';
import { formatClock, formatMinutes, isDecimalHours, parseDuration } from '../../shared/format';

/** What a row is about. Also the shape of a row that is kept while it has no time yet. */
export interface RowRef {
  projectId: number;
  projectCode: string;
  projectName: string;
  taskId: number | null;
  taskTitle: string | null;
  billable: boolean;
}

export interface GridRow extends RowRef {
  key: string;
  /** Minutes per day, Monday first. */
  days: number[];
  /** The entries behind each day's minutes: same project, task and day. */
  entries: TimeEntry[][];
  total: number;
}

export interface Grid {
  dates: string[];
  rows: GridRow[];
  dayTotals: number[];
  total: number;
}

export function rowKey(row: Pick<RowRef, 'projectId' | 'taskId'>): string {
  return `${row.projectId}:${row.taskId ?? ''}`;
}

export function cellKey(row: Pick<RowRef, 'projectId' | 'taskId'>, date: string): string {
  return `${rowKey(row)}@${date}`;
}

/**
 * One row per project + task, with the week's minutes spread across the seven days.
 * {@code emptyRows} are rows to show although they have no time (yet); a row that has
 * entries takes its names and its billable flag from them.
 */
export function buildGrid(weekStart: string, entries: TimeEntry[], emptyRows: RowRef[] = []): Grid {
  const dates = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const rows = new Map<string, GridRow>();
  const dayTotals = Array(7).fill(0) as number[];
  for (const e of entries) {
    const day = diffDays(weekStart, e.date);
    if (day < 0 || day > 6) {
      continue;
    }
    const key = rowKey({ projectId: e.projectId, taskId: e.taskId ?? null });
    let row = rows.get(key);
    if (!row) {
      row = newRow(key, {
        projectId: e.projectId,
        projectCode: e.projectCode,
        projectName: e.projectName,
        taskId: e.taskId ?? null,
        taskTitle: e.taskTitle ?? null,
        billable: e.billable,
      });
      rows.set(key, row);
    }
    row.days[day] += e.minutes;
    row.entries[day].push(e);
    row.total += e.minutes;
    row.billable = row.billable || e.billable;
    dayTotals[day] += e.minutes;
  }
  for (const ref of emptyRows) {
    const key = rowKey(ref);
    if (!rows.has(key)) {
      rows.set(key, newRow(key, ref));
    }
  }
  const sorted = [...rows.values()].sort(
    (a, b) => a.projectCode.localeCompare(b.projectCode) || (a.taskTitle ?? '').localeCompare(b.taskTitle ?? ''),
  );
  return { dates, rows: sorted, dayTotals, total: dayTotals.reduce((a, b) => a + b, 0) };
}

function newRow(key: string, ref: RowRef): GridRow {
  return {
    key,
    projectId: ref.projectId,
    projectCode: ref.projectCode,
    projectName: ref.projectName,
    taskId: ref.taskId,
    taskTitle: ref.taskTitle,
    billable: ref.billable,
    days: Array(7).fill(0) as number[],
    entries: Array.from({ length: 7 }, () => [] as TimeEntry[]),
    total: 0,
  };
}

/** Monday of the week named by ?week= (any day of it), or null when it is missing or not a date. */
export function weekOfParam(param: string | null | undefined): string | null {
  const date = parseIsoDate(param);
  return date ? weekStart(date) : null;
}

/** The entries of one cell, taken from the week as it is now. */
export function cellEntries(entries: TimeEntry[], row: Pick<RowRef, 'projectId' | 'taskId'>, date: string): TimeEntry[] {
  return entries.filter((e) => e.projectId === row.projectId && (e.taskId ?? null) === row.taskId && e.date === date);
}

/**
 * How a cell behaves. Only an empty cell and a cell with one finished entry can be typed
 * into: with several entries there is no telling which one a new total belongs to, and a
 * running timer has no time yet.
 */
export type CellMode = 'empty' | 'single' | 'several' | 'running';

export function cellMode(entries: TimeEntry[]): CellMode {
  if (entries.length === 0) {
    return 'empty';
  }
  if (entries.length > 1) {
    return 'several';
  }
  return entries[0].running ? 'running' : 'single';
}

/** What was typed into a cell: nothing (or a plain zero), a number of minutes, or something that can't be read. */
export type Typed = { kind: 'empty' } | { kind: 'minutes'; minutes: number } | { kind: 'invalid'; message: string };

export const MAX_CELL_MINUTES = 1440;

/**
 * Reads a cell: "1:30", "1.5", "1,5", "90m" and "1h 30m" are all an hour and a half, and a
 * number on its own is hours. Anything else is refused rather than guessed at, and so are
 * decimal hours that are not a whole number of minutes ("1.555"): nothing is rounded.
 */
export function parseCell(text: string): Typed {
  const trimmed = text.trim();
  if (!trimmed) {
    return { kind: 'empty' };
  }
  const minutes = parseDuration(trimmed);
  if (minutes === null) {
    return isDecimalHours(trimmed)
      ? { kind: 'invalid', message: 'That is not a whole number of minutes. Type it as hours and minutes, like 1:33' }
      : { kind: 'invalid', message: 'Enter time as 1:30, 1.5 or 90m' };
  }
  if (minutes > MAX_CELL_MINUTES) {
    return { kind: 'invalid', message: "A day can't take more than 24 hours" };
  }
  // A plain zero clears the cell.
  return minutes === 0 ? { kind: 'empty' } : { kind: 'minutes', minutes };
}

/** What a cell shows for a time that was read: "1:18" for 78 minutes, nothing for none. */
export function cellText(typed: Typed | number): string {
  const minutes = typeof typed === 'number' ? typed : typed.kind === 'minutes' ? typed.minutes : 0;
  return minutes ? formatClock(minutes) : '';
}

export type CellAction =
  | { kind: 'none' }
  | { kind: 'create'; minutes: number }
  | { kind: 'update'; entry: TimeEntry; minutes: number }
  | { kind: 'delete'; entry: TimeEntry }
  | { kind: 'refuse'; message: string };

/**
 * What to do with a cell once something was typed into it. A new time on one entry
 * updates that entry, and its note and billable flag stay as they are. An entry with a
 * note is not deleted from the grid, where the note can't be seen.
 */
export function cellAction(entries: TimeEntry[], typed: Typed): CellAction {
  if (typed.kind === 'invalid') {
    return { kind: 'refuse', message: typed.message };
  }
  const mode = cellMode(entries);
  if (mode === 'several') {
    return { kind: 'refuse', message: 'This cell holds several entries. Change them in the list below.' };
  }
  if (mode === 'running') {
    return { kind: 'refuse', message: 'A timer is running here. Stop it before changing the time.' };
  }
  if (mode === 'empty') {
    return typed.kind === 'minutes' ? { kind: 'create', minutes: typed.minutes } : { kind: 'none' };
  }
  const entry = entries[0];
  if (typed.kind === 'empty') {
    return entry.description
      ? { kind: 'refuse', message: 'This entry has a note. Delete it in the list below.' }
      : { kind: 'delete', entry };
  }
  return typed.minutes === entry.minutes ? { kind: 'none' } : { kind: 'update', entry, minutes: typed.minutes };
}

/** The week with {@code entry} added or replaced and the totals worked out again. An entry outside the week is dropped. */
export function withEntry(week: Week, entry: TimeEntry): Week {
  const day = diffDays(week.weekStart, entry.date);
  const others = week.entries.filter((e) => e.id !== entry.id);
  const entries = day < 0 || day > 6 ? others : [...others, entry].sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id);
  return withTotals(week, entries);
}

export function withoutEntry(week: Week, entryId: number): Week {
  return withTotals(
    week,
    week.entries.filter((e) => e.id !== entryId),
  );
}

function withTotals(week: Week, entries: TimeEntry[]): Week {
  const days = Array.from({ length: 7 }, (_, i) => ({ date: addDays(week.weekStart, i), minutes: 0 }));
  let totalMinutes = 0;
  let billableMinutes = 0;
  for (const e of entries) {
    const day = days[diffDays(week.weekStart, e.date)];
    if (day) {
      day.minutes += e.minutes;
    }
    totalMinutes += e.minutes;
    billableMinutes += e.billable ? e.minutes : 0;
  }
  return { ...week, entries, days, totalMinutes, billableMinutes };
}

export interface CellPosition {
  row: number;
  day: number;
}

export type ArrowKey = 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight';

/**
 * The cell an arrow key leads to: the next one in that direction that can take the focus,
 * or null at the edge of the grid. {@code focusable[row][day]} says which cells can.
 */
export function nextCell(focusable: boolean[][], from: CellPosition, key: ArrowKey): CellPosition | null {
  const step = key === 'ArrowUp' || key === 'ArrowLeft' ? -1 : 1;
  const vertical = key === 'ArrowUp' || key === 'ArrowDown';
  let { row, day } = from;
  for (;;) {
    if (vertical) {
      row += step;
    } else {
      day += step;
    }
    if (row < 0 || row >= focusable.length || day < 0 || day > 6) {
      return null;
    }
    if (focusable[row][day]) {
      return { row, day };
    }
  }
}

/**
 * Left and right only leave a cell once the caret has nowhere left to go inside it, so
 * the text can still be edited with them. Up and down always move on.
 */
export function caretAtEdge(key: ArrowKey, length: number, selectionStart: number | null, selectionEnd: number | null): boolean {
  if (key === 'ArrowUp' || key === 'ArrowDown') {
    return true;
  }
  const edge = key === 'ArrowLeft' ? 0 : length;
  return selectionStart === edge && selectionEnd === edge;
}

/** "ACME-APP, Tours catalogue, Tuesday": what a cell is called for somebody who can't see the grid. */
export function cellLabel(row: Pick<RowRef, 'projectCode' | 'projectName' | 'taskTitle'>, date: string): string {
  return `${row.projectCode}, ${row.taskTitle ?? row.projectName}, ${dayNameLong(date)}`;
}

/** The label of a cell that is not typed into, with the reason. */
export function severalLabel(row: Pick<RowRef, 'projectCode' | 'projectName' | 'taskTitle'>, date: string, entries: TimeEntry[]): string {
  const minutes = entries.reduce((sum, e) => sum + e.minutes, 0);
  return `${cellLabel(row, date)}: ${formatMinutes(minutes)} in ${entries.length} entries, change them in the list below`;
}

/** One cell of the grid as the page draws it. */
export interface CellView {
  key: string;
  date: string;
  day: number;
  /** An input to type into, a button that leads to the entry list (several entries), or plain text. */
  control: 'input' | 'several' | 'running' | 'text';
  minutes: number;
  /** What an input shows: what is being typed, otherwise the saved time. */
  text: string;
  label: string;
  note: string | null;
  /** Why what is in the cell can't be read. Nothing was sent, and the text is still there. */
  invalid: string | null;
  /** Why the last change to the cell was not saved. The cell shows the saved time again. */
  failed: string | null;
  saving: boolean;
}

export interface RowView {
  row: GridRow;
  label: string;
  /** A row without entries is only there because it is remembered, and can be taken away again. */
  removable: boolean;
  cells: CellView[];
}

export interface GridState {
  /** A submitted or approved week: nothing can be typed. */
  locked: boolean;
  /** What is being typed, by cell key. */
  drafts: Record<string, string>;
  /** Why the text of a cell can't be read, by cell key. */
  invalid: Record<string, string>;
  /** Why the last change to a cell was not saved, by cell key. */
  failed: Record<string, string>;
  /** The cells with a save on its way. */
  saving: Record<string, unknown>;
}

/** The rows and cells to draw: which cells take typing, what they show and what they are called. */
export function viewRows(grid: Grid, state: GridState): RowView[] {
  return grid.rows.map((row) => ({
    row,
    label: `${row.projectCode}, ${row.taskTitle ?? row.projectName}`,
    removable: !state.locked && row.entries.every((entries) => entries.length === 0),
    cells: grid.dates.map((date, day): CellView => {
      const key = cellKey(row, date);
      const entries = row.entries[day];
      const mode = cellMode(entries);
      const note = mode === 'single' ? (entries[0].description ?? null) : null;
      const minutes = row.days[day];
      return {
        key,
        date,
        day,
        control: state.locked ? 'text' : mode === 'several' || mode === 'running' ? mode : 'input',
        minutes,
        text: state.drafts[key] ?? cellText(minutes),
        label: mode === 'several' ? severalLabel(row, date, entries) : cellLabel(row, date) + (note ? ', has a note' : ''),
        note,
        invalid: state.invalid[key] ?? null,
        failed: state.failed[key] ?? null,
        saving: key in state.saving,
      };
    }),
  }));
}

/** Which cells of the drawn grid can take the focus, for {@link nextCell}. */
export function focusableCells(rows: RowView[]): boolean[][] {
  return rows.map((r) => r.cells.map((c) => c.control === 'input' || c.control === 'several'));
}
