import { canChangeTimeOff, capacityNote, holidaySet, timeOffLabel, timeOffProblem, workingDaysLabel } from './calendar-view';

describe('calendar view', () => {
  const holidays = holidaySet([{ id: 1, date: '2026-03-04', name: 'Holiday' }]);

  it('counts the working days of time off without weekends and holidays', () => {
    // Monday to the Monday after: six working days, five with the holiday.
    expect(workingDaysLabel('2026-03-02', '2026-03-09', new Set())).toBe('6 working days');
    expect(workingDaysLabel('2026-03-02', '2026-03-09', holidays)).toBe('5 working days');
    expect(workingDaysLabel('2026-03-04', '2026-03-05', holidays)).toBe('1 working day');
    expect(timeOffLabel({ firstDay: '2026-03-02', lastDay: '2026-03-03' }, holidays)).toContain('2 working days');
  });

  it('lets people change their own time off, and managers that of anybody', () => {
    expect(canChangeTimeOff({ userId: 3 }, 3, false)).toBe(true);
    expect(canChangeTimeOff({ userId: 4 }, 3, false)).toBe(false);
    expect(canChangeTimeOff({ userId: 4 }, 3, true)).toBe(true);
  });

  it('says what is missing from the form', () => {
    expect(timeOffProblem('', '2026-03-02')).toBe('Give the first and the last day.');
    expect(timeOffProblem('2026-03-03', '2026-03-02')).toBe('The last day is before the first.');
    expect(timeOffProblem('2026-03-02', '2026-03-02')).toBeNull();
  });

  it('explains a lower capacity in a workload cell', () => {
    expect(capacityNote({ holidays: 0, daysOff: 0 })).toBeNull();
    expect(capacityNote({ holidays: 1, daysOff: 2 })).toBe('Less capacity: 1 holiday, 2 days off');
  });
});
