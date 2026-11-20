// Calendar dates are handled as ISO strings ("2026-03-02") and converted through UTC
// so that time zones and daylight saving never shift a day.

const DAY_MS = 86_400_000;

export function toDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

export function toIso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** {@code value} if it is a real date written as yyyy-mm-dd, otherwise null. For dates that arrive in a URL. */
export function parseIsoDate(value: string | null | undefined): string | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null;
  }
  const date = toDate(value);
  return !Number.isNaN(date.getTime()) && toIso(date) === value ? value : null;
}

/** Today in the browser's local time zone. */
export function today(now: Date = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function addDays(iso: string, days: number): string {
  return toIso(new Date(toDate(iso).getTime() + days * DAY_MS));
}

/** Whole days from {@code a} to {@code b}; negative when b is earlier. */
export function diffDays(a: string, b: string): number {
  return Math.round((toDate(b).getTime() - toDate(a).getTime()) / DAY_MS);
}

/** 0 = Monday ... 6 = Sunday. */
export function weekday(iso: string): number {
  return (toDate(iso).getUTCDay() + 6) % 7;
}

export function isWeekend(iso: string): boolean {
  return weekday(iso) >= 5;
}

export function weekStart(iso: string): string {
  return addDays(iso, -weekday(iso));
}

export function minDate(dates: (string | undefined | null)[]): string | null {
  const valid = dates.filter((d): d is string => !!d);
  return valid.length ? valid.reduce((a, b) => (a < b ? a : b)) : null;
}

export function maxDate(dates: (string | undefined | null)[]): string | null {
  const valid = dates.filter((d): d is string => !!d);
  return valid.length ? valid.reduce((a, b) => (a > b ? a : b)) : null;
}

/** Working days (Mon-Fri) from start to end, both included. */
export function workingDaysBetween(start: string, end: string): number {
  let count = 0;
  for (let d = start; d <= end; d = addDays(d, 1)) {
    if (!isWeekend(d)) {
      count++;
    }
  }
  return count;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function dayName(iso: string): string {
  return DAYS[weekday(iso)];
}

const LONG_DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export function dayNameLong(iso: string): string {
  return LONG_DAYS[weekday(iso)];
}

export function monthName(iso: string): string {
  return MONTHS[toDate(iso).getUTCMonth()];
}

/** "3 Mar", or "3 Mar 2025" when the year differs from {@code reference}'s. */
export function formatDate(iso: string | null | undefined, reference: string = today()): string {
  if (!iso) {
    return '–';
  }
  const date = toDate(iso);
  const base = `${date.getUTCDate()} ${MONTHS[date.getUTCMonth()]}`;
  return iso.slice(0, 4) === reference.slice(0, 4) ? base : `${base} ${date.getUTCFullYear()}`;
}

export function formatRange(start?: string | null, end?: string | null): string {
  if (!start && !end) {
    return 'No dates';
  }
  if (!start || !end || start === end) {
    return formatDate(start ?? end);
  }
  return `${formatDate(start)} – ${formatDate(end)}`;
}

/** "2 hours ago", "yesterday", "3 Mar". */
export function timeAgo(instant: string, now: Date = new Date()): string {
  const seconds = Math.round((now.getTime() - Date.parse(instant)) / 1000);
  if (seconds < 60) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return hours === 1 ? '1 hour ago' : `${hours} hours ago`;
  }
  const days = Math.round(hours / 24);
  if (days === 1) {
    return 'yesterday';
  }
  if (days < 7) {
    return `${days} days ago`;
  }
  return formatDate(today(new Date(Date.parse(instant))), today(now));
}
