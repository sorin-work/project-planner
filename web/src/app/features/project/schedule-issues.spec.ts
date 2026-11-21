import { ScheduleIssue, Task } from '../../core/models';
import { formatDate } from '../../shared/dates';
import {
  brokenLinks, issueLines, issueText, linkIssue, linkKey, linkNote, moveCost, moveTo, planEndNote, scheduleIssuesText,
} from './schedule-issues';

const titles: Record<number, string> = { 1: 'Design', 2: 'Build', 3: 'Endpoints', 4: 'Copy' };
const titleOf = (id: number) => titles[id] ?? `#${id}`;

const early: ScheduleIssue = {
  type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 3, suggestedStart: '2026-03-09', suggestedDue: '2026-03-11',
};
const earlyToo: ScheduleIssue = { ...early, relatedTaskId: 4, days: 1 };
const undated: ScheduleIssue = { type: 'WAITS_FOR_UNSCHEDULED', taskId: 2, relatedTaskId: 4 };
const late: ScheduleIssue = { type: 'AFTER_PROJECT_END', taskId: 2, days: 1 };
const outside: ScheduleIssue = { type: 'OUTSIDE_PARENT', taskId: 3, relatedTaskId: 2, days: 2 };

function task(id: number, dueDate?: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: titleOf(id), status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, dueDate, ...extra,
  };
}

/** Design runs 2 to 6 March. Build waits for it and starts on the 4th, while Design is still running. */
const design = task(1, '2026-03-06', { startDate: '2026-03-02' });
const build = task(2, '2026-03-06', {
  startDate: '2026-03-04', predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }],
});
const SIXTH = formatDate('2026-03-06');

describe('schedule issues', () => {
  it('says what is wrong in a sentence', () => {
    expect(issueText(early, titleOf)).toBe('Starts 3 working days too early for Design');
    expect(issueText(undated, titleOf)).toBe('Waits for Copy, which has no due date, so the link is ignored');
    expect(issueText(late, titleOf)).toBe("Due 1 working day after the project's end date");
    expect(issueText(outside, titleOf)).toBe('Has 2 working days outside the dates of Build');
  });

  it('says the finish is too early for a link that holds the finish', () => {
    const finish: ScheduleIssue = { ...early, linkType: 'FF' };
    expect(issueText(finish, titleOf)).toBe('Ends 3 working days too early for Design');
    expect(linkNote({ ...early, linkType: 'SF' })).toBe('Ends 3 working days too early for this');
    expect(linkNote({ ...early, linkType: 'SS' })).toBe('Starts 3 working days too early for this');
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
    expect(moveTo([late, undated, early])).toEqual({ taskId: 2, startDate: '2026-03-09', dueDate: '2026-03-11', causes: [] });
    expect(moveTo([late, undated, outside])).toBeNull();
    expect(moveTo([{ ...early, suggestionCauses: ['AFTER_PROJECT_END'] }])?.causes).toEqual(['AFTER_PROJECT_END']);
  });

  it('says what the move would cost when the API reports that it breaks something else', () => {
    const fix = { taskId: 2, startDate: '2026-03-09', dueDate: '2026-03-11' };
    expect(moveCost({ ...fix, causes: [] })).toBeNull();
    expect(moveCost({ ...fix, causes: ['AFTER_PROJECT_END'] })).toBe("That puts it after the project's end date.");
    expect(moveCost({ ...fix, causes: ['OUTSIDE_PARENT'] })).toBe('That puts it outside the dates of its parent.');
    expect(moveCost({ ...fix, causes: ['AFTER_PROJECT_END', 'OUTSIDE_PARENT'] })).toBe(
      "That puts it after the project's end date and outside the dates of its parent.",
    );
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
      { taskId: 2, startDate: '2026-03-09', dueDate: '2026-03-11', causes: [] },
      undefined,
      undefined,
      undefined,
    ]);
    expect(new Set(lines.map((l) => l.key)).size).toBe(4);
  });

  it('explains a plan that ends after the latest due date by the tasks that start too early', () => {
    expect(planEndNote('2026-03-11', [design, build], [early, earlyToo])).toBe(
      `The latest due date is ${SIXTH}. The plan ends later because the critical path counts a task that starts too early from the first day its dependencies allow.`,
    );
    expect(planEndNote('2026-03-11', [design, build, task(3)], [early, { ...early, taskId: 3 }])).toBe(
      `The latest due date is ${SIXTH}. The plan ends later because the critical path counts 2 tasks that start too early from the first day their dependencies allow.`,
    );
  });

  it('no longer blames a finished task that overlapped, since the critical path keeps it where it was done', () => {
    // The API's finish is then the latest due date, and there is nothing to explain.
    const done = { ...build, status: 'DONE' as const };
    expect(planEndNote('2026-03-06', [design, done], [])).toBeNull();
  });

  it('counts the issues of a plan in a few words', () => {
    expect(scheduleIssuesText(1)).toBe('1 schedule issue');
    expect(scheduleIssuesText(3)).toBe('3 schedule issues');
  });

  it('still says something when the plan ends later for a reason it cannot see', () => {
    // A launch on a Saturday is counted on the Monday after.
    expect(planEndNote('2026-03-09', [task(1, '2026-03-07', { startDate: '2026-03-07' })], [late, undated])).toBe(
      `The latest due date is ${formatDate('2026-03-07')}. The plan ends later because the critical path counts every task from the first working day its dependencies allow.`,
    );
  });

  it('explains a plan that ends before the latest due date', () => {
    expect(planEndNote('2026-03-06', [design, task(3, '2026-03-10')], [])).toBe(
      `The latest due date is ${formatDate('2026-03-10')}. The plan ends earlier because the critical path only counts tasks that have a start date as well.`,
    );
    expect(planEndNote('2026-03-06', [task(1, '2026-03-07', { startDate: '2026-03-05' })], [])).toBe(
      `The latest due date is ${formatDate('2026-03-07')}. The plan ends earlier because the critical path counts working days only.`,
    );
  });

  it('says nothing when the plan ends on the latest due date or there is nothing to compare', () => {
    expect(planEndNote('2026-03-06', [design, build], [early])).toBeNull();
    expect(planEndNote(undefined, [design, build], [early])).toBeNull();
    expect(planEndNote(null, [design, build], [early])).toBeNull();
    expect(planEndNote('2026-03-12', [task(1)], [early])).toBeNull();
  });
});
