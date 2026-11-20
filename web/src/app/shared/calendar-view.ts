import { Holiday, TimeOff } from '../core/models';
import { Holidays, formatRange, workingDaysBetween } from './dates';

/** The holidays as the set the date helpers take. */
export function holidaySet(holidays: Holiday[]): Holidays {
  return new Set(holidays.map((h) => h.date));
}

/** "3 working days", counted without weekends and holidays, which nobody has to take off. */
export function workingDaysLabel(first: string, last: string, holidays: Holidays): string {
  const days = workingDaysBetween(first, last, holidays);
  return days === 1 ? '1 working day' : `${days} working days`;
}

/** What a line in the list of time off says: the days, and how many of them count. */
export function timeOffLabel(entry: Pick<TimeOff, 'firstDay' | 'lastDay'>, holidays: Holidays): string {
  return `${formatRange(entry.firstDay, entry.lastDay)} · ${workingDaysLabel(entry.firstDay, entry.lastDay, holidays)}`;
}

/** People remove their own time off. Owners and managers anybody's. */
export function canChangeTimeOff(entry: Pick<TimeOff, 'userId'>, myUserId: number | undefined, canManage: boolean): boolean {
  return canManage || entry.userId === myUserId;
}

/** Why the time off form can not be sent yet, or null when it can. */
export function timeOffProblem(firstDay: string, lastDay: string): string | null {
  if (!firstDay || !lastDay) {
    return 'Give the first and the last day.';
  }
  return lastDay < firstDay ? 'The last day is before the first.' : null;
}

/** The note in a workload cell whose capacity is lower than the weekly hours. Null when it is not. */
export function capacityNote(week: { holidays: number; daysOff: number }): string | null {
  const parts: string[] = [];
  if (week.holidays > 0) {
    parts.push(week.holidays === 1 ? '1 holiday' : `${week.holidays} holidays`);
  }
  if (week.daysOff > 0) {
    parts.push(week.daysOff === 1 ? '1 day off' : `${week.daysOff} days off`);
  }
  return parts.length ? `Less capacity: ${parts.join(', ')}` : null;
}
