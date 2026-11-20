import { ReportGroupBy, ReportPreset, TimeReportRow, UtilisationReport } from '../../core/models';
import { formatDate } from '../../shared/dates';

export const PRESETS: { value: ReportPreset; label: string }[] = [
  { value: 'this_week', label: 'This week' },
  { value: 'last_week', label: 'Last week' },
  { value: 'this_month', label: 'This month' },
  { value: 'last_month', label: 'Last month' },
];

export const GROUPS: { value: ReportGroupBy; label: string }[] = [
  { value: 'PROJECT', label: 'Project' },
  { value: 'CLIENT', label: 'Client' },
  { value: 'MEMBER', label: 'Person' },
  { value: 'TASK', label: 'Task' },
];

/** How long a row's bar is: its time as a percentage of the longest row. */
export function barWidth(row: Pick<TimeReportRow, 'minutes'>, rows: Pick<TimeReportRow, 'minutes'>[]): number {
  const longest = Math.max(0, ...rows.map((r) => r.minutes));
  return longest > 0 ? (row.minutes / longest) * 100 : 0;
}

/**
 * Money with its two decimals, "€1,234.50". Reports show amounts to the cent, so that the
 * rows add up to the total. (Elsewhere amounts are rounded to whole units.)
 */
export function formatAmount(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) {
    return '–';
  }
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

/** "62.5%", or a dash when there is nothing to compare with. */
export function formatShare(pct: number | null | undefined): string {
  return pct === null || pct === undefined ? '–' : `${pct.toFixed(1)}%`;
}

/** Says what utilisation is measured against and who is in it, in words for under the table. */
export function capacityNote(report: Pick<UtilisationReport, 'from' | 'to' | 'countedTo' | 'workingDays'>): string {
  const basis = "It uses each person's weekly hours as they are today, and does not know about holidays or time off yet.";
  const who = 'People who have left the organisation are not listed, so the total can be less than the Summary shows for the same range.';
  if (!report.countedTo) {
    return `This range has not begun, so there is no time and no capacity to compare yet. ${basis} ${who}`;
  }
  const days = report.workingDays === 1 ? '1 working day' : `${report.workingDays} working days`;
  const counted = `Capacity is the weekly hours from the Team page spread over Monday to Friday, for the ${days} from ${formatDate(
    report.from,
  )} to ${formatDate(report.countedTo)}.`;
  const cut = report.countedTo < report.to ? ' Days after today are not counted, neither their capacity nor time logged ahead on them.' : '';
  return `${counted}${cut} Utilisation is billable time as a share of that. ${basis} ${who}`;
}

/** The file name the API gives its CSV, for the download link. */
export function csvName(report: 'utilisation' | ReportGroupBy, from: string, to: string): string {
  const name = report === 'utilisation' ? 'utilisation' : `time-by-${report.toLowerCase()}`;
  return `${name}-${from}-to-${to}.csv`;
}
