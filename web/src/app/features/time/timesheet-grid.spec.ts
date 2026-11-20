import { TimeEntry, Week } from '../../core/models';
import {
  buildGrid,
  caretAtEdge,
  cellAction,
  cellEntries,
  cellLabel,
  cellMode,
  cellText,
  focusableCells,
  nextCell,
  parseCell,
  viewRows,
  weekOfParam,
  withEntry,
  withoutEntry,
} from './timesheet-grid';
import { formatElapsed } from './timer.service';

function entry(id: number, date: string, minutes: number, extra: Partial<TimeEntry> = {}): TimeEntry {
  return {
    id, userId: 1, userName: 'A', projectId: 1, projectName: 'Website', projectCode: 'WEB', date, minutes,
    billable: true, running: false, locked: false, ...extra,
  };
}

function week(entries: TimeEntry[]): Week {
  return { userId: 1, userName: 'A', weekStart: '2026-03-02', status: 'DRAFT', entries, days: [], totalMinutes: 0, billableMinutes: 0 };
}

const design = { taskId: 5, taskTitle: 'Design' };

describe('buildGrid', () => {
  it('sums minutes per row and day', () => {
    const grid = buildGrid('2026-03-02', [
      entry(1, '2026-03-02', 60, design),
      entry(2, '2026-03-02', 30, design),
      entry(3, '2026-03-04', 120),
      entry(4, '2026-03-08', 15, { projectId: 2, projectCode: 'ADM', projectName: 'Admin', billable: false }),
    ]);
    expect(grid.dates).toHaveLength(7);
    expect(grid.rows.map((r) => r.key)).toEqual(['2:', '1:', '1:5']);
    expect(grid.rows[2].days).toEqual([90, 0, 0, 0, 0, 0, 0]);
    expect(grid.rows[0].billable).toBe(false);
    expect(grid.dayTotals).toEqual([90, 0, 120, 0, 0, 0, 15]);
    expect(grid.total).toBe(225);
  });

  it('ignores entries outside the week', () => {
    const grid = buildGrid('2026-03-02', [entry(1, '2026-03-09', 60)]);
    expect(grid.rows).toHaveLength(0);
    expect(grid.total).toBe(0);
  });

  it('keeps the entries behind each cell', () => {
    const grid = buildGrid('2026-03-02', [entry(1, '2026-03-02', 60, design), entry(2, '2026-03-02', 30, design), entry(3, '2026-03-04', 120, design)]);
    const row = grid.rows[0];
    expect(row.entries.map((cell) => cell.map((e) => e.id))).toEqual([[1, 2], [], [3], [], [], [], []]);
  });

  it('shows rows that have no time yet, in their place, and lets entries speak for a row that has some', () => {
    const adm = { projectId: 2, projectCode: 'ADM', projectName: 'Admin', taskId: null, taskTitle: null, billable: false };
    const stale = { projectId: 1, projectCode: 'OLD', projectName: 'Old name', taskId: 5, taskTitle: 'Old title', billable: false };
    const grid = buildGrid('2026-03-02', [entry(1, '2026-03-02', 60, design)], [adm, stale]);

    expect(grid.rows.map((r) => [r.projectCode, r.taskTitle, r.total, r.billable])).toEqual([
      ['ADM', null, 0, false],
      ['WEB', 'Design', 60, true],
    ]);
    expect(grid.rows[0].days).toEqual([0, 0, 0, 0, 0, 0, 0]);
    expect(grid.total).toBe(60);
  });
});

describe('weekOfParam', () => {
  it('opens the week a link names, from any day of it', () => {
    expect(weekOfParam('2026-03-02')).toBe('2026-03-02');
    expect(weekOfParam('2026-03-05')).toBe('2026-03-02');
  });

  it('leaves the choice of week to the API when the link names none', () => {
    expect(weekOfParam(null)).toBeNull();
    expect(weekOfParam('last-week')).toBeNull();
  });
});

describe('parseCell', () => {
  it('reads an hour and a half in every way people write it', () => {
    for (const text of ['1.5', '1,5', '1:30', '90m', '1h 30m', ' 1h30m ', '1.5h', '90 min']) {
      expect(parseCell(text)).toEqual({ kind: 'minutes', minutes: 90 });
    }
    expect(parseCell('8')).toEqual({ kind: 'minutes', minutes: 480 });
    expect(parseCell('0:05')).toEqual({ kind: 'minutes', minutes: 5 });
    expect(parseCell('24')).toEqual({ kind: 'minutes', minutes: 1440 });
  });

  it('takes an empty cell or a plain zero as no time', () => {
    for (const text of ['', '   ', '0', '0h', '0:00', '0,0']) {
      expect(parseCell(text)).toEqual({ kind: 'empty' });
    }
  });

  it('refuses what it can not read instead of guessing', () => {
    for (const text of ['abc', '1:75', '1:5', '-1', '1h30', '1 30', '1.5.2', '.5', '1;30', 'two hours']) {
      expect(parseCell(text)).toEqual({ kind: 'invalid', message: 'Enter time as 1:30, 1.5 or 90m' });
    }
  });

  it('refuses more than a day', () => {
    // A number on its own is hours, so 90 is not ninety minutes.
    expect(parseCell('90')).toEqual({ kind: 'invalid', message: "A day can't take more than 24 hours" });
    expect(parseCell('24:01')).toEqual({ kind: 'invalid', message: "A day can't take more than 24 hours" });
    expect(parseCell('24.5')).toEqual({ kind: 'invalid', message: "A day can't take more than 24 hours" });
  });

  it('does not round decimal hours to minutes nobody typed', () => {
    const notWholeMinutes = 'That is not a whole number of minutes. Type it as hours and minutes, like 1:33';
    for (const text of ['1.555', '24.008', '0.001', '1,333', '0.01h']) {
      expect(parseCell(text)).toEqual({ kind: 'invalid', message: notWholeMinutes });
    }
    // An hour and eighteen minutes, not half past one: the cell shows 1:18 as soon as it is left.
    expect(parseCell('1.30')).toEqual({ kind: 'minutes', minutes: 78 });
    expect(parseCell('1,30')).toEqual({ kind: 'minutes', minutes: 78 });
    expect(cellText(parseCell('1.30'))).toBe('1:18');
    expect(cellText(parseCell('90m'))).toBe('1:30');
    expect(cellText(parseCell('0'))).toBe('');
    expect(cellText(45)).toBe('0:45');
    expect(cellText(0)).toBe('');
  });
});

describe('cellAction', () => {
  const one = entry(1, '2026-03-02', 60);
  const noted = entry(2, '2026-03-02', 60, { description: 'Call with Ruth', billable: false });
  const timer = entry(3, '2026-03-02', 0, { running: true });

  it('creates an entry in an empty cell and leaves an empty one alone', () => {
    expect(cellAction([], parseCell('1:30'))).toEqual({ kind: 'create', minutes: 90 });
    expect(cellAction([], parseCell(''))).toEqual({ kind: 'none' });
    expect(cellAction([], parseCell('0'))).toEqual({ kind: 'none' });
  });

  it('updates the one entry of a cell, and only when the time changed', () => {
    expect(cellAction([one], parseCell('2'))).toEqual({ kind: 'update', entry: one, minutes: 120 });
    expect(cellAction([one], parseCell('60m'))).toEqual({ kind: 'none' });
    // The entry goes along, so that its note and billable flag can be sent back unchanged.
    expect(cellAction([noted], parseCell('0:45'))).toEqual({ kind: 'update', entry: noted, minutes: 45 });
  });

  it('deletes the entry of a cleared cell, unless it has a note', () => {
    expect(cellAction([one], parseCell(''))).toEqual({ kind: 'delete', entry: one });
    expect(cellAction([one], parseCell('0'))).toEqual({ kind: 'delete', entry: one });
    expect(cellAction([noted], parseCell(''))).toEqual({
      kind: 'refuse',
      message: 'This entry has a note. Delete it in the list below.',
    });
  });

  it('never touches a cell with several entries or a running timer', () => {
    expect(cellMode([one, noted])).toBe('several');
    expect(cellMode([timer])).toBe('running');
    for (const text of ['2', '']) {
      expect(cellAction([one, noted], parseCell(text)).kind).toBe('refuse');
      expect(cellAction([timer], parseCell(text)).kind).toBe('refuse');
    }
  });

  it('passes on why a text was not read', () => {
    expect(cellAction([one], parseCell('abc'))).toEqual({ kind: 'refuse', message: 'Enter time as 1:30, 1.5 or 90m' });
  });
});

describe('applying a saved entry', () => {
  const monday = entry(1, '2026-03-02', 60, design);
  const friday = entry(3, '2026-03-06', 30, { billable: false });

  it('adds an entry and works the totals out again', () => {
    const result = withEntry(week([friday]), monday);
    expect(result.entries.map((e) => e.id)).toEqual([1, 3]);
    expect(result.totalMinutes).toBe(90);
    expect(result.billableMinutes).toBe(60);
    expect(result.days.map((d) => d.minutes)).toEqual([60, 0, 0, 0, 30, 0, 0]);
    expect(result.days[4].date).toBe('2026-03-06');
  });

  it('replaces an entry that changed, and drops one that moved to another week', () => {
    const changed = withEntry(week([monday, friday]), { ...monday, minutes: 120 });
    expect(changed.entries.map((e) => [e.id, e.minutes])).toEqual([[1, 120], [3, 30]]);
    expect(changed.totalMinutes).toBe(150);

    const moved = withEntry(week([monday, friday]), { ...monday, date: '2026-03-09' });
    expect(moved.entries.map((e) => e.id)).toEqual([3]);
    expect(moved.totalMinutes).toBe(30);
  });

  it('takes an entry out', () => {
    const result = withoutEntry(week([monday, friday]), 3);
    expect(result.entries).toEqual([monday]);
    expect(result.totalMinutes).toBe(60);
    expect(result.billableMinutes).toBe(60);
  });

  it('finds the entries of a cell by project, task and day', () => {
    const all = [monday, entry(2, '2026-03-02', 15), entry(4, '2026-03-03', 20, design)];
    expect(cellEntries(all, { projectId: 1, taskId: 5 }, '2026-03-02')).toEqual([monday]);
    expect(cellEntries(all, { projectId: 1, taskId: null }, '2026-03-02').map((e) => e.id)).toEqual([2]);
    expect(cellEntries(all, { projectId: 9, taskId: null }, '2026-03-02')).toEqual([]);
  });
});

describe('viewRows', () => {
  const state = { locked: false, drafts: {}, invalid: {}, failed: {}, saving: {} };
  const entries = [
    entry(1, '2026-03-02', 90, design),
    entry(2, '2026-03-03', 60, { ...design, description: 'Call with Ruth' }),
    entry(3, '2026-03-04', 30, design),
    entry(4, '2026-03-04', 45, design),
    entry(5, '2026-03-05', 0, { ...design, running: true }),
  ];
  const grid = buildGrid('2026-03-02', entries, [
    { projectId: 2, projectCode: 'ADM', projectName: 'Admin', taskId: null, taskTitle: null, billable: false },
  ]);

  it('lets empty cells and cells with one entry be typed into', () => {
    const [admin, web] = viewRows(grid, state);
    expect(web.cells.map((c) => c.control)).toEqual(['input', 'input', 'several', 'running', 'input', 'input', 'input']);
    expect(web.cells.map((c) => c.text)).toEqual(['1:30', '1:00', '1:15', '', '', '', '']);
    expect(admin.cells.every((c) => c.control === 'input' && c.text === '')).toBe(true);
    // Only a row without any entry can be taken away again.
    expect([admin.removable, web.removable]).toEqual([true, false]);
  });

  it('names every cell by project, task and day', () => {
    const [admin, web] = viewRows(grid, state);
    expect(web.cells[0].label).toBe('WEB, Design, Monday');
    expect(web.cells[1].label).toBe('WEB, Design, Tuesday, has a note');
    expect(web.cells[1].note).toBe('Call with Ruth');
    expect(web.cells[2].label).toBe('WEB, Design, Wednesday: 1h 15m in 2 entries, change them in the list below');
    expect(admin.cells[6].label).toBe('ADM, Admin, Sunday');
    expect(cellLabel({ projectCode: 'ADM', projectName: 'Admin', taskTitle: null }, '2026-03-07')).toBe('ADM, Admin, Saturday');
  });

  it('shows what is being typed, what can not be read, what is on its way and what was not saved', () => {
    const [, web] = viewRows(grid, {
      locked: false,
      drafts: { '1:5@2026-03-02': '2', '1:5@2026-03-06': 'abc' },
      invalid: { '1:5@2026-03-06': 'Enter time as 1:30, 1.5 or 90m' },
      failed: { '1:5@2026-03-03': 'Website is finished; reopen it to log more time' },
      saving: { '1:5@2026-03-02': true },
    });
    expect(web.cells[0]).toMatchObject({ key: '1:5@2026-03-02', text: '2', saving: true, invalid: null, failed: null });
    expect(web.cells[4]).toMatchObject({ text: 'abc', saving: false, invalid: 'Enter time as 1:30, 1.5 or 90m' });
    // A change that was not saved leaves the saved time in the cell, with the reason.
    expect(web.cells[1]).toMatchObject({ text: '1:00', failed: 'Website is finished; reopen it to log more time' });
  });

  it('has nothing to type into in a submitted or approved week', () => {
    const rows = viewRows(buildGrid('2026-03-02', entries), {
      ...state,
      locked: true,
      drafts: { '1:5@2026-03-02': '2' },
      failed: { '1:5@2026-03-02': 'The timesheet is submitted' },
    });
    expect(rows[0].cells.every((c) => c.control === 'text')).toBe(true);
    // What was not saved is still told, also when nothing can be typed any more.
    expect(rows[0].cells[0].failed).toBe('The timesheet is submitted');
    expect(rows[0].removable).toBe(false);
    expect(focusableCells(rows)).toEqual([[false, false, false, false, false, false, false]]);
  });
});

describe('moving between cells', () => {
  // Three rows. The middle one has a running timer on Wednesday, which takes no focus.
  const focusable = [
    [true, true, true, true, true, true, true],
    [true, true, false, true, true, true, true],
    [true, true, true, true, true, true, true],
  ];

  it('goes to the neighbouring cell', () => {
    expect(nextCell(focusable, { row: 0, day: 0 }, 'ArrowDown')).toEqual({ row: 1, day: 0 });
    expect(nextCell(focusable, { row: 1, day: 0 }, 'ArrowUp')).toEqual({ row: 0, day: 0 });
    expect(nextCell(focusable, { row: 0, day: 3 }, 'ArrowRight')).toEqual({ row: 0, day: 4 });
    expect(nextCell(focusable, { row: 0, day: 3 }, 'ArrowLeft')).toEqual({ row: 0, day: 2 });
  });

  it('skips a cell that takes no focus', () => {
    expect(nextCell(focusable, { row: 0, day: 2 }, 'ArrowDown')).toEqual({ row: 2, day: 2 });
    expect(nextCell(focusable, { row: 1, day: 1 }, 'ArrowRight')).toEqual({ row: 1, day: 3 });
    expect(nextCell(focusable, { row: 1, day: 3 }, 'ArrowLeft')).toEqual({ row: 1, day: 1 });
  });

  it('stops at the edges', () => {
    expect(nextCell(focusable, { row: 0, day: 0 }, 'ArrowUp')).toBeNull();
    expect(nextCell(focusable, { row: 2, day: 0 }, 'ArrowDown')).toBeNull();
    expect(nextCell(focusable, { row: 0, day: 0 }, 'ArrowLeft')).toBeNull();
    expect(nextCell(focusable, { row: 0, day: 6 }, 'ArrowRight')).toBeNull();
  });

  it('keeps left and right for the text until the caret is at its end', () => {
    expect(caretAtEdge('ArrowUp', 5, 2, 2)).toBe(true);
    expect(caretAtEdge('ArrowDown', 5, 0, 5)).toBe(true);
    expect(caretAtEdge('ArrowLeft', 5, 0, 0)).toBe(true);
    expect(caretAtEdge('ArrowLeft', 5, 2, 2)).toBe(false);
    expect(caretAtEdge('ArrowRight', 5, 5, 5)).toBe(true);
    // Everything selected, as it is when a cell gets the focus: the first press only puts the caret down.
    expect(caretAtEdge('ArrowRight', 5, 0, 5)).toBe(false);
    expect(caretAtEdge('ArrowLeft', 5, 0, 5)).toBe(false);
    expect(caretAtEdge('ArrowLeft', 0, 0, 0)).toBe(true);
    expect(caretAtEdge('ArrowRight', 0, 0, 0)).toBe(true);
  });
});

describe('formatElapsed', () => {
  it('shows hours, minutes and seconds', () => {
    expect(formatElapsed(0)).toBe('0:00:00');
    expect(formatElapsed(3725)).toBe('1:02:05');
  });
});
