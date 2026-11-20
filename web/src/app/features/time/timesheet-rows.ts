import { userKeyPrefix } from '../../core/user-storage';
import { addDays } from '../../shared/dates';
import { GridRow, RowRef, rowKey } from './timesheet-grid';

// Rows only exist through their entries, so a row without time has to be kept somewhere
// else: per person, organisation and week in localStorage, as the rows somebody added,
// copied from last week or emptied. The keys start with the person's id, so nobody else
// who signs in on the same browser gets them, and signing out removes them.

/** Rows of weeks older than this many weeks are forgotten when a week is saved. */
const KEEP_WEEKS = 12;

type RowStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>;

/** Whose rows, in which organisation. */
export interface RowOwner {
  userId: number;
  orgId: number;
}

export function rowsStorageKey(owner: RowOwner, weekStart: string): string {
  return `${userKeyPrefix(owner.userId)}rows.${owner.orgId}.${weekStart}`;
}

/** The rows kept for a week. Anything that is not a list of rows reads as none. */
export function readRows(store: Pick<Storage, 'getItem'>, owner: RowOwner, weekStart: string): RowRef[] {
  try {
    const parsed: unknown = JSON.parse(store.getItem(rowsStorageKey(owner, weekStart)) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter(isRowRef).map(toRowRef) : [];
  } catch {
    return [];
  }
}

/** Saves the rows of a week (no rows removes the key) and forgets weeks from long ago. */
export function writeRows(store: RowStore, owner: RowOwner, weekStart: string, rows: RowRef[]): void {
  try {
    if (rows.length) {
      store.setItem(rowsStorageKey(owner, weekStart), JSON.stringify(rows.map(toRowRef)));
    } else {
      store.removeItem(rowsStorageKey(owner, weekStart));
    }
    const oldest = rowsStorageKey(owner, addDays(weekStart, -7 * KEEP_WEEKS));
    const mine = rowsStorageKey(owner, '');
    const stale: string[] = [];
    for (let i = 0; i < store.length; i++) {
      const key = store.key(i);
      if (key?.startsWith(mine) && key < oldest) {
        stale.push(key);
      }
    }
    stale.forEach((key) => store.removeItem(key));
  } catch {
    // Private mode or a full store: the rows just aren't remembered.
  }
}

/** {@code rows} followed by those of {@code more} that are not in it yet. */
export function addRows(rows: RowRef[], more: RowRef[]): RowRef[] {
  const keys = new Set(rows.map(rowKey));
  const result = [...rows];
  for (const row of more) {
    if (!keys.has(rowKey(row))) {
      keys.add(rowKey(row));
      result.push(toRowRef(row));
    }
  }
  return result;
}

export function removeRow(rows: RowRef[], row: Pick<RowRef, 'projectId' | 'taskId'>): RowRef[] {
  return rows.filter((r) => rowKey(r) !== rowKey(row));
}

/**
 * Last week's rows that this week does not have yet, without their time. Rows of projects
 * that take no more time (finished ones, which are not in {@code openProjectIds}) are skipped.
 */
export function rowsToCopy(lastWeek: GridRow[], thisWeek: Pick<RowRef, 'projectId' | 'taskId'>[], openProjectIds: Set<number>): RowRef[] {
  const present = new Set(thisWeek.map(rowKey));
  return lastWeek.filter((row) => openProjectIds.has(row.projectId) && !present.has(rowKey(row))).map(toRowRef);
}

function isRowRef(value: unknown): value is RowRef {
  const row = value as Partial<RowRef> | null;
  return (
    !!row &&
    typeof row === 'object' &&
    typeof row.projectId === 'number' &&
    typeof row.projectCode === 'string' &&
    typeof row.projectName === 'string' &&
    (row.taskId === null || typeof row.taskId === 'number') &&
    (row.taskTitle === null || typeof row.taskTitle === 'string')
  );
}

/** Just the row's own fields, for a row that carries more (a grid row) or comes from storage. */
export function toRowRef(row: RowRef): RowRef {
  return {
    projectId: row.projectId,
    projectCode: row.projectCode,
    projectName: row.projectName,
    taskId: row.taskId,
    taskTitle: row.taskTitle,
    billable: row.billable !== false,
  };
}
