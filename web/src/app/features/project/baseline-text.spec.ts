import { BaselineComparison, BaselineTaskChange } from '../../core/models';
import { formatDate } from '../../shared/dates';
import {
  baselineDates, baselineLabel, baselineLabels, changeText, changedItems, countsText, endText, milestoneChanges, milestoneText, workingDays,
} from './baseline-text';

function task(taskId: number, extra: Partial<BaselineTaskChange>): BaselineTaskChange {
  return { taskId, title: `Task ${taskId}`, milestone: false, change: 'UNCHANGED', existedThen: true, existsNow: true, ...extra };
}

const slipped = task(1, {
  change: 'SLIPPED', baselineStart: '2026-03-02', baselineDue: '2026-03-06', currentStart: '2026-03-04', currentDue: '2026-03-10',
  startSlipDays: 2, finishSlipDays: 2,
});
const startsLater = task(2, {
  change: 'SLIPPED', baselineStart: '2026-03-02', baselineDue: '2026-03-06', currentStart: '2026-03-03', currentDue: '2026-03-06',
  startSlipDays: 1, finishSlipDays: 0,
});
const pulledIn = task(3, {
  change: 'PULLED_IN', baselineStart: '2026-03-09', baselineDue: '2026-03-13', currentStart: '2026-03-05', currentDue: '2026-03-11',
  startSlipDays: -2, finishSlipDays: -2,
});
const added = task(4, { change: 'ADDED', existedThen: false, currentStart: '2026-03-16', currentDue: '2026-03-17' });
const givenDates = task(5, { change: 'ADDED', currentStart: '2026-03-23', currentDue: '2026-03-24' });
const removed = task(6, { change: 'REMOVED', existsNow: false, baselineStart: '2026-03-09', baselineDue: '2026-03-10' });
const cleared = task(7, { change: 'REMOVED', baselineDue: '2026-03-18' });
const same = task(8, { baselineStart: '2026-03-02', baselineDue: '2026-03-02', currentStart: '2026-03-02', currentDue: '2026-03-02', startSlipDays: 0, finishSlipDays: 0 });

const comparison: BaselineComparison = {
  baseline: { id: 9, projectId: 1, name: 'Signed off', reference: true, createdAt: '', tasks: 7 },
  tasks: [slipped, startsLater, pulledIn, added, givenDates, removed, cleared, same],
  phases: [
    { phaseId: 20, name: 'Build', change: 'SLIPPED', existedThen: true, existsNow: true, baselineStart: '2026-03-09', baselineEnd: '2026-03-13',
      currentStart: '2026-03-09', currentEnd: '2026-03-19', startSlipDays: 0, finishSlipDays: 4 },
    { phaseId: 21, name: 'Launch', change: 'UNCHANGED', existedThen: true, existsNow: true },
  ],
  projectEnd: { baseline: '2026-03-20', current: '2026-03-27', slipDays: 5, change: 'SLIPPED' },
  planEnd: { baseline: '2026-03-18', current: '2026-03-24', slipDays: 4, change: 'SLIPPED' },
  counts: { unchanged: 1, slipped: 2, pulledIn: 1, added: 2, removed: 2 },
  baselineEstimateHours: 50,
  currentEstimateHours: 80,
};

describe('baseline text', () => {
  it('says how many working days a task moved, and by its finish unless the finish stayed', () => {
    expect(changeText(slipped)).toBe('Finishes 2 working days later than in the baseline');
    expect(changeText(startsLater)).toBe('Starts 1 working day later than in the baseline');
    expect(changeText(pulledIn)).toBe('Finishes 2 working days earlier than in the baseline');
    expect(changeText(same)).toBe('As in the baseline');
    expect(workingDays(-1)).toBe('1 working day');
  });

  it('tells a new task from one that was given dates, and a deleted task from one whose dates were cleared', () => {
    expect(changeText(added)).toBe('Added since the baseline');
    expect(changeText(givenDates)).toBe('Had no dates in the baseline');
    expect(changeText(removed)).toBe('Removed since the baseline');
    expect(changeText(cleared)).toBe('Has had its dates cleared since the baseline');
  });

  it('puts the dates of then and now after the sentence', () => {
    expect(baselineLabel(slipped)).toBe(
      `Finishes 2 working days later than in the baseline: ${formatDate('2026-03-02')} – ${formatDate('2026-03-06')} in the baseline, `
        + `now ${formatDate('2026-03-04')} – ${formatDate('2026-03-10')}`,
    );
    expect(baselineLabel(added)).toBe(`Added since the baseline: now ${formatDate('2026-03-16')} – ${formatDate('2026-03-17')}`);
    expect(baselineLabel(cleared)).toBe(`Has had its dates cleared since the baseline: ${formatDate('2026-03-18')} in the baseline`);
  });

  it('describes the end of the project and of the plan', () => {
    expect(endText(comparison.projectEnd)).toBe(
      `Ends 5 working days later than in the baseline (${formatDate('2026-03-20')}, now ${formatDate('2026-03-27')})`,
    );
    expect(endText({ baseline: '2026-03-20', current: '2026-03-20', slipDays: 0, change: 'UNCHANGED' })).toBe(
      `Ends as in the baseline (${formatDate('2026-03-20')})`,
    );
    expect(endText({ current: '2026-03-20', change: 'ADDED' })).toBe(`Had no end date in the baseline, now ${formatDate('2026-03-20')}`);
    expect(endText({ baseline: '2026-03-20', current: '2026-03-19', slipDays: -1, change: 'PULLED_IN' }, 'The plan ends')).toBe(
      `The plan ends 1 working day earlier than in the baseline (${formatDate('2026-03-20')}, now ${formatDate('2026-03-19')})`,
    );
  });

  it('gives a thin bar to every task that is still there and had dates, a one-day bar for a single date', () => {
    const dates = baselineDates(comparison);
    expect([...dates.keys()]).toEqual([1, 2, 3, 7, 8]);
    expect(dates.get(1)).toEqual({ start: '2026-03-02', due: '2026-03-06' });
    expect(dates.get(7)).toEqual({ start: '2026-03-18', due: '2026-03-18' });
    expect(baselineDates(null).size).toBe(0);
    expect(baselineLabels(comparison).has(6)).toBe(false);
    expect(baselineLabels(comparison).get(4)).toContain('Added since the baseline');
  });

  it('lists what changed, phases first, deleted tasks included and unchanged ones left out', () => {
    const items = changedItems(comparison);
    expect(items.map((i) => i.name)).toEqual(['Phase Build', 'Task 1', 'Task 2', 'Task 3', 'Task 4', 'Task 5', 'Task 6', 'Task 7']);
    expect(items.find((i) => i.id === 6)?.gone).toBe(true);
    expect(items[0].text).toContain('Finishes 4 working days later');
    expect(changedItems(null)).toEqual([]);
  });

  it('counts the changes in words', () => {
    expect(countsText(comparison.counts)).toBe('2 slipped, 1 pulled in, 2 added, 2 removed');
    expect(countsText({ unchanged: 4, slipped: 0, pulledIn: 0, added: 0, removed: 0 })).toBe('Every task is where it was');
  });

  it('says how late or early a milestone is, and which milestones there are', () => {
    const launch = task(10, { milestone: true, change: 'SLIPPED', baselineDue: '2026-03-18', currentDue: '2026-03-20', startSlipDays: 2, finishSlipDays: 2 });
    const early = { ...launch, change: 'PULLED_IN' as const, startSlipDays: -1, finishSlipDays: -1 };
    expect(milestoneText(launch)).toBe('2 working days late');
    expect(milestoneText(early)).toBe('1 working day early');
    expect(milestoneText({ ...launch, change: 'UNCHANGED', startSlipDays: 0, finishSlipDays: 0 })).toBe('On time');
    expect(milestoneText({ ...launch, change: 'ADDED', existedThen: false })).toBe('New');
    expect(milestoneText({ ...launch, change: 'REMOVED', existsNow: false })).toBe('Removed');
    expect(milestoneChanges({ ...comparison, tasks: [slipped, launch] })).toEqual([launch]);
  });
});
