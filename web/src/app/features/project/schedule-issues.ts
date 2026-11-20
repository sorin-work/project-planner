import { ScheduleIssue, ScheduleIssueType, Task } from '../../core/models';
import { formatDate, maxDate } from '../../shared/dates';

/** The two dates the API suggests for a task that starts too early. */
export interface MoveTo {
  taskId: number;
  startDate: string;
  dueDate: string;
  /** What the API says the move would get the task reported for instead. Empty when it costs nothing. */
  causes: ScheduleIssueType[];
}

/** One row of the issue list. */
export interface IssueLine {
  key: string;
  taskId: number;
  title: string;
  text: string;
  /** Only on the first row of a task: one move fixes all of its links. */
  fix?: MoveTo;
}

type TitleOf = (taskId: number) => string;

/** Names a dependency arrow by its two ends. */
export function linkKey(predecessorId: number, successorId: number): string {
  return `${predecessorId}>${successorId}`;
}

function isLinkIssue(issue: ScheduleIssue): boolean {
  return issue.type === 'STARTS_TOO_EARLY' || issue.type === 'WAITS_FOR_UNSCHEDULED';
}

/** The dependencies that do not hold or are not enforced, as link keys. */
export function brokenLinks(issues: ScheduleIssue[]): Set<string> {
  const keys = new Set<string>();
  for (const issue of issues) {
    if (isLinkIssue(issue) && issue.relatedTaskId !== undefined) {
      keys.add(linkKey(issue.relatedTaskId, issue.taskId));
    }
  }
  return keys;
}

function workingDays(days: number | undefined): string {
  return `${days ?? 0} working day${days === 1 ? '' : 's'}`;
}

/** A finish-to-finish or start-to-finish link holds the finish, so that is what is too early. */
function endsOrStarts(issue: ScheduleIssue): string {
  return issue.linkType === 'FF' || issue.linkType === 'SF' ? 'Ends' : 'Starts';
}

/** A whole sentence about an issue, for a list where the task's own title stands next to it. */
export function issueText(issue: ScheduleIssue, titleOf: TitleOf): string {
  const related = issue.relatedTaskId === undefined ? '' : titleOf(issue.relatedTaskId);
  switch (issue.type) {
    case 'STARTS_TOO_EARLY':
      return `${endsOrStarts(issue)} ${workingDays(issue.days)} too early for ${related}`;
    case 'WAITS_FOR_UNSCHEDULED':
      return `Waits for ${related}, which has no due date, so the link is ignored`;
    case 'AFTER_PROJECT_END':
      return `Due ${workingDays(issue.days)} after the project's end date`;
    case 'OUTSIDE_PARENT':
      return `Has ${workingDays(issue.days)} outside the dates of ${related}`;
  }
}

/** The short form for the "Waits for" list, where the other task's title is already on the row. */
export function linkNote(issue: ScheduleIssue): string {
  return issue.type === 'STARTS_TOO_EARLY'
    ? `${endsOrStarts(issue)} ${workingDays(issue.days)} too early for this`
    : 'Has no due date, so this link is ignored';
}

/** The issue with the task waiting for {@code predecessorId}, if that link has one. */
export function linkIssue(issues: ScheduleIssue[], predecessorId: number): ScheduleIssue | undefined {
  return issues.find((i) => isLinkIssue(i) && i.relatedTaskId === predecessorId);
}

/** The move that fixes a task's broken links, or null if none of these issues comes with one. */
export function moveTo(issues: ScheduleIssue[]): MoveTo | null {
  const issue = issues.find((i) => i.suggestedStart && i.suggestedDue);
  return issue
    ? { taskId: issue.taskId, startDate: issue.suggestedStart!, dueDate: issue.suggestedDue!, causes: issue.suggestionCauses ?? [] }
    : null;
}

/**
 * What the move would cost, to show next to the button: the suggested dates only
 * look at the dependencies, and the API says when they break something else.
 * Null when they do not.
 */
export function moveCost(fix: MoveTo): string | null {
  const where: string[] = [];
  if (fix.causes.includes('AFTER_PROJECT_END')) {
    where.push("after the project's end date");
  }
  if (fix.causes.includes('OUTSIDE_PARENT')) {
    where.push('outside the dates of its parent');
  }
  return where.length ? `That puts it ${where.join(' and ')}.` : null;
}

export function issueLines(issues: ScheduleIssue[], titleOf: TitleOf): IssueLine[] {
  const offered = new Set<number>();
  return issues.map((issue) => {
    const line: IssueLine = {
      key: `${issue.type}:${issue.taskId}:${issue.relatedTaskId ?? ''}`,
      taskId: issue.taskId,
      title: titleOf(issue.taskId),
      text: issueText(issue, titleOf),
    };
    const fix = offered.has(issue.taskId) ? null : moveTo([issue]);
    if (fix) {
      offered.add(issue.taskId);
      line.fix = fix;
    }
    return line;
  });
}

/**
 * Finished tasks that start on or before the due date of a task they wait for.
 * The schedule check leaves finished tasks out, but the critical path still moves
 * them, so they can push the end of the plan without an issue to show for it.
 * This compares the dates as they are: a lag or a weekend in between is not
 * looked at, that would be the API's arithmetic.
 */
export function finishedOverlaps(tasks: Task[]): Task[] {
  const byId = new Map(tasks.map((t) => [t.id, t]));
  return tasks.filter((task) => {
    const start = task.startDate;
    if (task.status !== 'DONE' || !start || !task.dueDate) {
      return false;
    }
    // Only plain finish-to-start links: a start-to-start link or a lead overlaps on purpose.
    return task.predecessors.some((d) => {
      if (d.type !== 'FS' || d.lagDays < 0) {
        return false;
      }
      const waitedFor = byId.get(d.predecessorId);
      return !!waitedFor?.startDate && !!waitedFor.dueDate && start <= waitedFor.dueDate;
    });
  });
}

/**
 * Why "Plan ends" on the timeline is not the latest due date, or null when the
 * two are the same day. The critical path works the end out again from the
 * dependencies, in working days and from tasks with both dates; the latest due
 * date is the last one that was typed in. The note names the cause that can be
 * seen from here: tasks with an open too-early issue, finished tasks that
 * overlapped a task they waited for, or a task the critical path does not count.
 */
export function planEndNote(finish: string | null | undefined, tasks: Task[], issues: ScheduleIssue[]): string | null {
  const latestDue = maxDate(tasks.map((t) => t.dueDate));
  if (!finish || !latestDue || finish === latestDue) {
    return null;
  }
  const latest = `The latest due date is ${formatDate(latestDue)}.`;
  if (finish < latestDue) {
    const withoutStart = tasks.some((t) => !!t.dueDate && t.dueDate > finish && !t.startDate);
    return withoutStart
      ? `${latest} The plan ends earlier because the critical path only counts tasks that have a start date as well.`
      : `${latest} The plan ends earlier because the critical path counts working days only.`;
  }
  const early = new Set(issues.filter((i) => i.type === 'STARTS_TOO_EARLY').map((i) => i.taskId)).size;
  const finished = finishedOverlaps(tasks).length;
  const causes: string[] = [];
  if (early) {
    causes.push(early === 1 ? 'a task that starts too early' : `${early} tasks that start too early`);
  }
  if (finished) {
    causes.push(
      finished === 1
        ? 'a finished task that overlapped a task it waited for'
        : `${finished} finished tasks that overlapped a task they waited for`,
    );
  }
  if (!causes.length) {
    return `${latest} The plan ends later because the critical path counts every task from the first working day its dependencies allow.`;
  }
  const their = early + finished === 1 ? 'its' : 'their';
  return `${latest} The plan ends later because the critical path counts ${causes.join(' and ')} from the first day ${their} dependencies allow.`;
}
