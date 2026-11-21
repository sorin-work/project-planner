import { ApprovalSkip, ApproveResult, SheetToApprove, TimesheetSummary } from '../../core/models';
import { formatDate } from '../../shared/dates';

/**
 * The weeks a click approves, as they are on screen: with the time each was submitted, so that
 * a week withdrawn and submitted again since is left out. A manager's own week is left out
 * here already, since only an owner or another manager may approve it.
 */
export function sheetsToApprove(
  sheets: TimesheetSummary[],
  chosen: ReadonlySet<number> | null,
  myUserId: number | null,
  isOwner: boolean,
): SheetToApprove[] {
  return sheets
    .filter((s) => chosen === null || chosen.has(s.id))
    .filter((s) => isOwner || s.userId !== myUserId)
    .filter((s): s is TimesheetSummary & { submittedAt: string } => !!s.submittedAt)
    .map((s) => ({ id: s.id, submittedAt: s.submittedAt }));
}

/** Whether the row of a week gets a box to tick: everything but a manager's own week. */
export function canApprove(sheet: TimesheetSummary, myUserId: number | null, isOwner: boolean): boolean {
  return isOwner || sheet.userId !== myUserId;
}

const REASONS: Record<ApprovalSkip, string> = {
  CHANGED: 'withdrawn and submitted again since the list was loaded. Open it to check it again.',
  NOT_SUBMITTED: 'no longer waiting for review.',
  OWN_WEEK: 'your own week. Another manager or an owner approves it.',
  NOT_FOUND: 'no longer there.',
};

export interface ApproveSummary {
  /** "Approved 3 weeks." or "No weeks approved." */
  headline: string;
  /** One line per week left out, with the person, the week and why. */
  leftOut: string[];
}

/** What an approval request did, for the notice above the queue. */
export function approveSummary(result: ApproveResult, sheets: TimesheetSummary[]): ApproveSummary {
  const count = result.approved.length;
  const headline = count === 0 ? 'No weeks approved.' : `Approved ${count} ${count === 1 ? 'week' : 'weeks'}.`;
  const leftOut = result.skipped.map((skip) => {
    const sheet = sheets.find((s) => s.id === skip.timesheetId);
    const what = sheet ? `${sheet.userName}, week of ${formatDate(sheet.weekStart)}` : 'A week';
    return `${what}: ${REASONS[skip.reason]}`;
  });
  return { headline, leftOut };
}

/** The ticks without these weeks. */
export function untick(ticked: ReadonlyMap<number, string | undefined>, ids: number[]): Map<number, string | undefined> {
  const next = new Map(ticked);
  ids.forEach((id) => next.delete(id));
  return next;
}

/**
 * The ticks that still hold after the queue was loaded again: the week is still in it, and
 * it was not submitted again since it was ticked.
 */
export function keepTicked(
  ticked: ReadonlyMap<number, string | undefined>,
  sheets: TimesheetSummary[],
): Map<number, string | undefined> {
  return new Map(sheets.filter((s) => ticked.has(s.id) && ticked.get(s.id) === s.submittedAt).map((s) => [s.id, s.submittedAt]));
}
