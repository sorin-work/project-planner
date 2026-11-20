import { GridRow, RowRef } from './timesheet-grid';
import { forgetUser } from '../../core/user-storage';
import { addRows, readRows, removeRow, rowsStorageKey, rowsToCopy, writeRows } from './timesheet-rows';

function row(projectId: number, taskId: number | null, extra: Partial<RowRef> = {}): RowRef {
  return { projectId, projectCode: `P${projectId}`, projectName: `Project ${projectId}`, taskId, taskTitle: taskId ? `Task ${taskId}` : null, billable: true, ...extra };
}

function gridRow(projectId: number, taskId: number | null): GridRow {
  return { ...row(projectId, taskId), key: `${projectId}:${taskId ?? ''}`, days: [60, 0, 0, 0, 0, 0, 0], entries: [], total: 60 };
}

describe('remembered rows', () => {
  beforeEach(() => localStorage.clear());

  const tom = { userId: 1, orgId: 7 };
  const lena = { userId: 3, orgId: 7 };

  it('keeps rows per person, organisation and week', () => {
    writeRows(localStorage, tom, '2026-03-02', [row(1, 5), row(2, null, { billable: false })]);
    writeRows(localStorage, { userId: 1, orgId: 8 }, '2026-03-02', [row(3, null)]);

    expect(rowsStorageKey(tom, '2026-03-02')).toBe('pp.user.1.rows.7.2026-03-02');
    expect(readRows(localStorage, tom, '2026-03-02')).toEqual([row(1, 5), row(2, null, { billable: false })]);
    expect(readRows(localStorage, tom, '2026-03-09')).toEqual([]);
    expect(readRows(localStorage, { userId: 1, orgId: 8 }, '2026-03-02')).toEqual([row(3, null)]);
    // The next person on this browser, in the same organisation, starts without them.
    expect(readRows(localStorage, lena, '2026-03-02')).toEqual([]);
  });

  it('forgets a person\'s rows when they sign out, and nobody else\'s', () => {
    writeRows(localStorage, tom, '2026-03-02', [row(1, 5)]);
    writeRows(localStorage, lena, '2026-03-02', [row(2, null)]);
    writeRows(localStorage, { userId: 11, orgId: 7 }, '2026-03-02', [row(3, null)]);
    localStorage.setItem('pp.orgId', '7');

    forgetUser(localStorage, 1);

    expect(Object.keys(localStorage).sort()).toEqual(['pp.orgId', 'pp.user.11.rows.7.2026-03-02', 'pp.user.3.rows.7.2026-03-02']);
  });

  it('reads anything unexpected as no rows', () => {
    localStorage.setItem(rowsStorageKey(tom, '2026-03-02'), 'not json');
    expect(readRows(localStorage, tom, '2026-03-02')).toEqual([]);
    localStorage.setItem(rowsStorageKey(tom, '2026-03-02'), JSON.stringify([{ projectId: 'x' }, row(1, null), 42]));
    expect(readRows(localStorage, tom, '2026-03-02')).toEqual([row(1, null)]);
  });

  it('drops the key when the last row goes and forgets weeks from long ago', () => {
    writeRows(localStorage, tom, '2025-11-03', [row(1, null)]);
    writeRows(localStorage, tom, '2026-02-23', [row(1, null)]);
    writeRows(localStorage, { userId: 1, orgId: 8 }, '2025-11-03', [row(1, null)]);
    writeRows(localStorage, lena, '2025-11-03', [row(1, null)]);
    localStorage.setItem('pp.orgId', '7');

    writeRows(localStorage, tom, '2026-03-02', [row(2, null)]);
    writeRows(localStorage, tom, '2026-02-23', []);

    expect(Object.keys(localStorage).sort()).toEqual([
      'pp.orgId',
      'pp.user.1.rows.7.2026-03-02',
      'pp.user.1.rows.8.2025-11-03',
      'pp.user.3.rows.7.2025-11-03',
    ]);
  });

  it('adds a row once and removes it again', () => {
    const rows = addRows([row(1, 5)], [row(1, 5), row(1, null), row(1, null)]);
    expect(rows).toEqual([row(1, 5), row(1, null)]);
    expect(removeRow(rows, { projectId: 1, taskId: 5 })).toEqual([row(1, null)]);
  });

  it('copies the rows of last week that are new here and still take time', () => {
    const lastWeek = [gridRow(1, 5), gridRow(1, null), gridRow(2, 9), gridRow(3, null)];
    // Project 3 is finished, and the row for task 5 is already there.
    const copied = rowsToCopy(lastWeek, [{ projectId: 1, taskId: 5 }], new Set([1, 2]));

    expect(copied).toEqual([row(1, null), row(2, 9)]);
  });
});
