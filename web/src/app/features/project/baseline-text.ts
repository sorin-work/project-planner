import { BaselineComparison, BaselineDateChange, BaselinePhaseChange, BaselineTaskChange } from '../../core/models';
import { formatDate, formatRange } from '../../shared/dates';

/**
 * Words for a comparison with a baseline. The API has already decided what changed
 * and by how many working days, so nothing here counts days: it only says what the
 * numbers mean, for the task panel, the list under the timeline and the bars' labels.
 */

/** "1 working day", "3 working days". */
export function workingDays(days: number): string {
  const n = Math.abs(days);
  return `${n} working day${n === 1 ? '' : 's'}`;
}

/** "later" or "earlier" for a number of working days that is not zero. */
function direction(days: number): string {
  return days > 0 ? 'later' : 'earlier';
}

/** The move that decides, as the API decides it: the finish, or the start when the finish stayed. */
function moved(finish: number | undefined, start: number | undefined): string | null {
  if (finish) {
    return `Finishes ${workingDays(finish)} ${direction(finish)}`;
  }
  if (start) {
    return `Starts ${workingDays(start)} ${direction(start)}`;
  }
  return null;
}

/** One sentence on how a task or a phase compares with the baseline. */
export function changeText(change: BaselineTaskChange | BaselinePhaseChange): string {
  switch (change.change) {
    case 'SLIPPED':
    case 'PULLED_IN':
      return `${moved(change.finishSlipDays, change.startSlipDays)} than in the baseline`;
    case 'ADDED':
      return change.existedThen ? 'Had no dates in the baseline' : 'Added since the baseline';
    case 'REMOVED':
      return change.existsNow ? 'Has had its dates cleared since the baseline' : 'Removed since the baseline';
    default:
      return change.existedThen ? 'As in the baseline' : 'Not in the baseline';
  }
}

function thenOf(change: BaselineTaskChange | BaselinePhaseChange): [string | undefined, string | undefined] {
  return 'taskId' in change ? [change.baselineStart, change.baselineDue] : [change.baselineStart, change.baselineEnd];
}

function nowOf(change: BaselineTaskChange | BaselinePhaseChange): [string | undefined, string | undefined] {
  return 'taskId' in change ? [change.currentStart, change.currentDue] : [change.currentStart, change.currentEnd];
}

/** "2 Mar – 6 Mar in the baseline, now 4 Mar – 10 Mar", or as much of it as there is. */
export function datesText(change: BaselineTaskChange | BaselinePhaseChange): string {
  const [thenStart, thenEnd] = thenOf(change);
  const [nowStart, nowEnd] = nowOf(change);
  const then = thenStart || thenEnd ? `${formatRange(thenStart, thenEnd)} in the baseline` : null;
  const now = nowStart || nowEnd ? `now ${formatRange(nowStart, nowEnd)}` : null;
  return [then, now].filter((part) => part !== null).join(', ');
}

/** The sentence and the dates together, for a label read out instead of a grey bar. */
export function baselineLabel(change: BaselineTaskChange | BaselinePhaseChange): string {
  const dates = datesText(change);
  return dates ? `${changeText(change)}: ${dates}` : changeText(change);
}

/** "Ends 5 working days later than in the baseline (20 Mar, now 27 Mar)". */
export function endText(end: BaselineDateChange, what = 'Ends'): string {
  if (end.change === 'UNCHANGED') {
    return end.baseline ? `${what} as in the baseline (${formatDate(end.baseline)})` : `No end date then or now`;
  }
  if (end.change === 'ADDED') {
    return `Had no end date in the baseline, now ${formatDate(end.current)}`;
  }
  if (end.change === 'REMOVED') {
    return `Had an end date in the baseline (${formatDate(end.baseline)}), none now`;
  }
  const days = end.slipDays ?? 0;
  return `${what} ${workingDays(days)} ${direction(days)} than in the baseline (${formatDate(end.baseline)}, now ${formatDate(end.current)})`;
}

/** Where each task's thin bar goes: the baseline's dates of every task that still exists and had dates. */
export function baselineDates(comparison: BaselineComparison | null): Map<number, { start: string; due: string }> {
  const dates = new Map<number, { start: string; due: string }>();
  for (const t of comparison?.tasks ?? []) {
    const start = t.baselineStart ?? t.baselineDue;
    const due = t.baselineDue ?? t.baselineStart;
    if (t.existsNow && start && due) {
      dates.set(t.taskId, { start, due });
    }
  }
  return dates;
}

/** A label per task that is still there, for the bar the thin bar belongs to. */
export function baselineLabels(comparison: BaselineComparison | null): Map<number, string> {
  return new Map((comparison?.tasks ?? []).filter((t) => t.existsNow).map((t) => [t.taskId, baselineLabel(t)]));
}

/** What has changed, for the list under the timeline: every task and phase that is not as it was. */
export function changedItems(comparison: BaselineComparison | null): { key: string; kind: 'task' | 'phase'; id: number; name: string; text: string; gone: boolean }[] {
  if (!comparison) {
    return [];
  }
  const phases = comparison.phases
    .filter((p) => p.change !== 'UNCHANGED')
    .map((p) => ({ key: `p${p.phaseId}`, kind: 'phase' as const, id: p.phaseId, name: `Phase ${p.name}`, text: baselineLabel(p), gone: !p.existsNow }));
  const tasks = comparison.tasks
    .filter((t) => t.change !== 'UNCHANGED')
    .map((t) => ({ key: `t${t.taskId}`, kind: 'task' as const, id: t.taskId, name: t.title, text: baselineLabel(t), gone: !t.existsNow }));
  return [...phases, ...tasks];
}

/** "3 slipped, 1 pulled in, 2 added, 1 removed", leaving out what is zero. */
export function countsText(counts: BaselineComparison['counts']): string {
  const parts = [
    [counts.slipped, 'slipped'],
    [counts.pulledIn, 'pulled in'],
    [counts.added, 'added'],
    [counts.removed, 'removed'],
  ] as const;
  const text = parts.filter(([n]) => n > 0).map(([n, what]) => `${n} ${what}`).join(', ');
  return text || 'Every task is where it was';
}

/** The milestones of a comparison, the deleted ones included: what a client asks about. */
export function milestoneChanges(comparison: BaselineComparison | null): BaselineTaskChange[] {
  return (comparison?.tasks ?? []).filter((t) => t.milestone);
}

/** A milestone's change in a table cell: "2 working days late", "On time", "New". */
export function milestoneText(change: BaselineTaskChange): string {
  const days = change.finishSlipDays || change.startSlipDays || 0;
  switch (change.change) {
    case 'SLIPPED':
      return `${workingDays(days)} late`;
    case 'PULLED_IN':
      return `${workingDays(days)} early`;
    case 'ADDED':
      return change.existedThen ? 'Had no date' : 'New';
    case 'REMOVED':
      return change.existsNow ? 'Date cleared' : 'Removed';
    default:
      return change.existedThen ? 'On time' : 'No date';
  }
}
