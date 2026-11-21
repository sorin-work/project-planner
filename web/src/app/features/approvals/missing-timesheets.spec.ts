import { MissingTimesheet } from '../../core/models';
import {
  canRemind,
  groupMissing,
  loggedOfCapacity,
  paramOfTab,
  remindAllIds,
  remindSummary,
  tabOfParam,
} from './missing-timesheets';

function person(userId: number, userName: string, totalMinutes: number, extra: Partial<MissingTimesheet> = {}): MissingTimesheet {
  return { userId, userName, status: 'DRAFT', totalMinutes, capacityHours: 40, away: false, remindedToday: false, ...extra };
}

const tom = person(1, 'Tom Becker', 2250);
const samir = person(2, 'Samir Haddad', 1800, { status: 'REJECTED' });
const lena = person(3, 'Lena Novak', 0, { capacityHours: 32 });
const priya = person(4, 'Priya Shah', 600, { remindedToday: true });
const maya = person(5, 'Maya Collins', 300);
const everyone = [tom, samir, lena, priya, maya];

describe('approvals tabs', () => {
  it('reads the tab from the link and falls back to the approval queue', () => {
    expect(tabOfParam('missing')).toBe('MISSING');
    expect(tabOfParam('approved')).toBe('APPROVED');
    expect(tabOfParam(null)).toBe('SUBMITTED');
    expect(tabOfParam('draft')).toBe('SUBMITTED');
    expect(tabOfParam('nonsense')).toBe('SUBMITTED');
  });

  it('keeps the default tab out of the link', () => {
    expect(paramOfTab('SUBMITTED')).toBeNull();
    expect(paramOfTab('MISSING')).toBe('missing');
    expect(tabOfParam(paramOfTab('REJECTED'))).toBe('REJECTED');
  });
});

describe('missing timesheets', () => {
  it('lists weeks without any time apart from the late ones', () => {
    const groups = groupMissing(everyone);
    expect(groups.late).toEqual([tom, samir, priya, maya]);
    expect(groups.noTime).toEqual([lena]);
  });

  it('offers a reminder once a day and never to yourself', () => {
    expect(canRemind(tom, 5)).toBe(true);
    expect(canRemind(lena, 5)).toBe(true);
    expect(canRemind(priya, 5)).toBe(false);
    expect(canRemind(maya, 5)).toBe(false);
  });

  it('reminds everybody late at once, but not people without time', () => {
    expect(remindAllIds(everyone, 5)).toEqual([1, 2]);
    expect(remindAllIds([lena, priya], 5)).toEqual([]);
  });

  it('shows the time logged against the weekly capacity', () => {
    expect(loggedOfCapacity(tom)).toBe('37h 30m of 40h');
    expect(loggedOfCapacity(lena)).toBe('0h of 32h');
    // A whole week off: no capacity to compare with, but the hours of somebody who worked anyway.
    expect(loggedOfCapacity({ ...tom, totalMinutes: 60, capacityHours: 0, away: true })).toBe('1h logged, away');
  });
});

describe('remindSummary', () => {
  it('names one or two people and counts more', () => {
    expect(remindSummary({ weekStart: '2026-03-02', reminded: [1], skipped: [] }, everyone)).toBe('Reminded Tom Becker.');
    expect(remindSummary({ weekStart: '2026-03-02', reminded: [1, 2], skipped: [] }, everyone)).toBe(
      'Reminded Tom Becker and Samir Haddad.',
    );
    expect(remindSummary({ weekStart: '2026-03-02', reminded: [1, 2, 3], skipped: [] }, everyone)).toBe('Reminded 3 people.');
  });

  it('says who was left out and why', () => {
    const result = {
      weekStart: '2026-03-02',
      reminded: [1],
      skipped: [
        { userId: 4, reason: 'ALREADY_REMINDED' as const },
        { userId: 2, reason: 'NOT_MISSING' as const },
        { userId: 99, reason: 'NOT_MISSING' as const },
        { userId: 3, reason: 'DAILY_LIMIT' as const },
      ],
    };
    expect(remindSummary(result, everyone)).toBe(
      'Reminded Tom Becker. Priya Shah was already reminded about this week today. ' +
        'Lena Novak has had enough reminders for one day. 2 people are no longer on the list.',
    );
  });

  it('says so when nothing went out', () => {
    expect(remindSummary({ weekStart: '2026-03-02', reminded: [], skipped: [] }, everyone)).toBe('No reminders sent.');
  });
});
