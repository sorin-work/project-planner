import { TimesheetSummary } from '../../core/models';
import { approveSummary, canApprove, keepTicked, sheetsToApprove, untick } from './bulk-approval';

function sheet(id: number, userId: number, userName: string, submittedAt?: string): TimesheetSummary {
  return {
    id, userId, userName, weekStart: '2024-03-04', status: 'SUBMITTED', submittedAt,
    totalMinutes: 2400, billableMinutes: 1800, billableAmount: 2250,
  };
}

const tom = sheet(11, 1, 'Tom Becker', '2026-03-09T08:01:02.123456Z');
const samir = sheet(12, 2, 'Samir Haddad', '2026-03-09T09:00:00Z');
const mine = sheet(13, 5, 'Maya Collins', '2026-03-09T10:00:00Z');
const queue = [tom, samir, mine];

describe('sheetsToApprove', () => {
  it('sends each week with the time it was submitted, as it was on screen', () => {
    expect(sheetsToApprove(queue, new Set([12]), 5, false)).toEqual([{ id: 12, submittedAt: '2026-03-09T09:00:00Z' }]);
    expect(sheetsToApprove(queue, null, 5, true).map((s) => s.id)).toEqual([11, 12, 13]);
    // The time goes back exactly as it came, microseconds and all.
    expect(sheetsToApprove([tom], null, 5, true)[0].submittedAt).toBe('2026-03-09T08:01:02.123456Z');
  });

  it("leaves a manager's own week out, and a week without a submission time", () => {
    expect(sheetsToApprove(queue, null, 5, false).map((s) => s.id)).toEqual([11, 12]);
    expect(sheetsToApprove(queue, new Set([13]), 5, false)).toEqual([]);
    expect(sheetsToApprove([sheet(14, 3, 'Lena Novak')], null, 5, true)).toEqual([]);
    expect(canApprove(mine, 5, false)).toBe(false);
    expect(canApprove(mine, 5, true)).toBe(true);
    expect(canApprove(tom, 5, false)).toBe(true);
  });
});

describe('approveSummary', () => {
  it('counts what was approved and names each week left out with the reason', () => {
    const summary = approveSummary(
      {
        approved: [11],
        skipped: [
          { timesheetId: 12, reason: 'CHANGED' },
          { timesheetId: 13, reason: 'OWN_WEEK' },
          { timesheetId: 99, reason: 'NOT_FOUND' },
        ],
      },
      queue,
    );
    expect(summary.headline).toBe('Approved 1 week.');
    expect(summary.leftOut).toEqual([
      'Samir Haddad, week of 4 Mar 2024: withdrawn and submitted again since the list was loaded. Open it to check it again.',
      'Maya Collins, week of 4 Mar 2024: your own week. Another manager or an owner approves it.',
      'A week: no longer there.',
    ]);
  });

  it('says so when nothing was approved', () => {
    const summary = approveSummary({ approved: [], skipped: [{ timesheetId: 11, reason: 'NOT_SUBMITTED' }] }, queue);
    expect(summary.headline).toBe('No weeks approved.');
    expect(summary.leftOut).toEqual(['Tom Becker, week of 4 Mar 2024: no longer waiting for review.']);
    expect(approveSummary({ approved: [11, 12], skipped: [] }, queue)).toEqual({ headline: 'Approved 2 weeks.', leftOut: [] });
  });
});

describe('ticks', () => {
  const ticked = new Map([[11, tom.submittedAt], [12, samir.submittedAt]]);

  it('keeps a tick only while the week is in the queue as it was when it was ticked', () => {
    const again = { ...samir, submittedAt: '2026-03-10T07:00:00Z' };
    expect([...keepTicked(ticked, [tom, again, mine]).keys()]).toEqual([11]);
    expect([...keepTicked(ticked, [samir]).keys()]).toEqual([12]);
  });

  it('drops the ticks of the weeks a request approved or left out', () => {
    expect([...untick(ticked, [12, 99]).keys()]).toEqual([11]);
    expect(ticked.size).toBe(2);
  });
});
