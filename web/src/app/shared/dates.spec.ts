import {
  addDays,
  dayName,
  diffDays,
  formatDate,
  formatRange,
  isWeekend,
  maxDate,
  minDate,
  timeAgo,
  today,
  weekStart,
  weekday,
  workingDaysBetween,
} from './dates';

describe('dates', () => {
  // 2026-03-02 is a Monday.
  it('adds and diffs days across month ends and DST changes', () => {
    expect(addDays('2026-02-27', 3)).toBe('2026-03-02');
    expect(addDays('2026-03-28', 2)).toBe('2026-03-30');
    expect(diffDays('2026-03-02', '2026-03-09')).toBe(7);
    expect(diffDays('2026-03-09', '2026-03-02')).toBe(-7);
  });

  it('knows weekdays and weekends', () => {
    expect(weekday('2026-03-02')).toBe(0);
    expect(weekday('2026-03-08')).toBe(6);
    expect(isWeekend('2026-03-07')).toBe(true);
    expect(isWeekend('2026-03-06')).toBe(false);
    expect(dayName('2026-03-04')).toBe('Wed');
  });

  it('starts weeks on Monday', () => {
    expect(weekStart('2026-03-02')).toBe('2026-03-02');
    expect(weekStart('2026-03-08')).toBe('2026-03-02');
    expect(weekStart('2026-03-09')).toBe('2026-03-09');
  });

  it('counts working days inclusively', () => {
    expect(workingDaysBetween('2026-03-02', '2026-03-06')).toBe(5);
    expect(workingDaysBetween('2026-03-06', '2026-03-09')).toBe(2);
    expect(workingDaysBetween('2026-03-07', '2026-03-08')).toBe(0);
  });

  it('finds the earliest and latest date', () => {
    expect(minDate(['2026-03-05', undefined, '2026-03-01'])).toBe('2026-03-01');
    expect(maxDate([null, '2026-03-05', '2026-03-01'])).toBe('2026-03-05');
    expect(minDate([])).toBeNull();
  });

  it('formats dates, adding the year only when it differs', () => {
    expect(formatDate('2026-03-02', '2026-06-01')).toBe('2 Mar');
    expect(formatDate('2025-12-24', '2026-06-01')).toBe('24 Dec 2025');
    expect(formatDate(null)).toBe('–');
    expect(formatRange(undefined, undefined)).toBe('No dates');
  });

  it('uses the local calendar day for today', () => {
    expect(today(new Date(2026, 2, 2, 23, 30))).toBe('2026-03-02');
  });

  it('describes how long ago something happened', () => {
    const now = new Date('2026-03-10T12:00:00Z');
    expect(timeAgo('2026-03-10T11:59:30Z', now)).toBe('just now');
    expect(timeAgo('2026-03-10T11:15:00Z', now)).toBe('45 min ago');
    expect(timeAgo('2026-03-10T09:00:00Z', now)).toBe('3 hours ago');
    expect(timeAgo('2026-03-09T10:00:00Z', now)).toBe('yesterday');
    expect(timeAgo('2026-03-06T12:00:00Z', now)).toBe('4 days ago');
  });
});
