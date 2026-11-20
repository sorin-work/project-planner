import { ScheduleIssue, Task } from '../../core/models';
import { formatDate } from '../../shared/dates';
import { brokenLinks, issueLines, issueText, linkIssue, linkKey, linkNote, moveTo, planEndNote } from './schedule-issues';

const titles: Record<number, string> = { 1: 'Design', 2: 'Build', 3: 'Endpoints', 4: 'Copy' };
const titleOf = (id: number) => titles[id] ?? `#${id}`;

const early: ScheduleIssue = {
  type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-11',
};
const earlyToo: ScheduleIssue = { ...early, relatedTaskId: 4, days: 1 };
const undated: ScheduleIssue = { type: 'WAITS_FOR_UNSCHEDULED', taskId: 2, relatedTaskId: 4 };
const late: ScheduleIssue = { type: 'AFTER_PROJECT_END', taskId: 2, days: 1 };
const outside: ScheduleIssue = { type: 'OUTSIDE_PARENT', taskId: 3, relatedTaskId: 2, days: 2 };

function task(id: number, dueDate?: string): Task {
  return {
    id, projectId: 1, title: titleOf(id), status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, dueDate,
  };
}

describe('schedule issues', () => {
  it('says what is wrong in a sentence', () => {
    expect(issueText(early, titleOf)).toBe('Starts 3 working days too early for Design');
    expect(issueText(undated, titleOf)).toBe('Waits for Copy, which has no due date, so the link is ignored');
    expect(issueText(late, titleOf)).toBe("Due 1 working day after the project's end date");
    expect(issueText(outside, titleOf)).toBe('Has 2 working days outside the dates of Build');
  });

  it('has a short form for the row of the task it waits for', () => {
    expect(linkNote(earlyToo)).toBe('Starts 1 working day too early for this');
    expect(linkNote(undated)).toBe('Has no due date, so this link is ignored');
  });

  it('marks the links the issues are about, and only those', () => {
    const keys = brokenLinks([early, undated, late, outside]);
    expect([...keys].sort()).toEqual([linkKey(1, 2), linkKey(4, 2)].sort());
    // Endpoints is a subtask of Build, not something that waits for it.
    expect(keys.has(linkKey(2, 3))).toBe(false);
  });

  it('finds the issue of one link', () => {
    expect(linkIssue([late, undated, early], 1)).toBe(early);
    expect(linkIssue([late, undated, early], 4)).toBe(undated);
    expect(linkIssue([late, outside], 2)).toBeUndefined();
  });

  it('takes the move from the issue as it came', () => {
    expect(moveTo([late, undated, early])).toEqual({ taskId: 2, startDate: '2026-03-09', dueDate: '2026-03-11' });
    expect(moveTo([late, undated, outside])).toBeNull();
  });

  it('lists one row per issue and offers the move once per task', () => {
    const lines = issueLines([early, earlyToo, late, outside], titleOf);
    expect(lines.map((l) => `${l.title}: ${l.text}`)).toEqual([
      'Build: Starts 3 working days too early for Design',
      'Build: Starts 1 working day too early for Copy',
      "Build: Due 1 working day after the project's end date",
      'Endpoints: Has 2 working days outside the dates of Build',
    ]);
    expect(lines.map((l) => l.fix)).toEqual([
      { taskId: 2, startDate: '2026-03-09', dueDate: '2026-03-11' },
      undefined,
      undefined,
      undefined,
    ]);
    expect(new Set(lines.map((l) => l.key)).size).toBe(4);
  });

  it('explains a plan that ends after the latest due date', () => {
    const tasks = [task(1, '2026-03-06'), task(2, '2026-03-06'), task(3)];
    expect(planEndNote('2026-03-11', tasks, [early, earlyToo])).toBe(
      `The latest due date is ${formatDate('2026-03-06')}. The plan ends later because a task that starts too early is counted from the first day it can start.`,
    );
    expect(planEndNote('2026-03-11', tasks, [early, { ...early, taskId: 3 }])).toContain('2 tasks that start too early are counted');
  });

  it('says nothing when the two dates agree or no open task starts too early', () => {
    const tasks = [task(1, '2026-03-06'), task(2, '2026-03-11')];
    expect(planEndNote('2026-03-11', tasks, [early])).toBeNull();
    expect(planEndNote('2026-03-12', tasks, [late, undated])).toBeNull();
    expect(planEndNote(undefined, tasks, [early])).toBeNull();
    expect(planEndNote('2026-03-12', [task(1)], [early])).toBeNull();
  });
});
