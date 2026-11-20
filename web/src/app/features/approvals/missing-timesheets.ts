import { MissingTimesheet, RemindResult, ReminderSkip, TimesheetStatus } from '../../core/models';
import { formatHours, formatMinutes } from '../../shared/format';

export type ApprovalsTab = TimesheetStatus | 'MISSING';

export const APPROVALS_TABS: ApprovalsTab[] = ['SUBMITTED', 'APPROVED', 'REJECTED', 'MISSING'];

/** The tab named by ?tab=; the approval queue when there is none or it is unknown. */
export function tabOfParam(param: string | null | undefined): ApprovalsTab {
  const tab = (param ?? '').toUpperCase() as ApprovalsTab;
  return APPROVALS_TABS.includes(tab) ? tab : 'SUBMITTED';
}

/** The ?tab= value for a tab. The approval queue is the default and has none. */
export function paramOfTab(tab: ApprovalsTab): string | null {
  return tab === 'SUBMITTED' ? null : tab.toLowerCase();
}

export interface MissingGroups {
  /** Time was logged but the week was not submitted. */
  late: MissingTimesheet[];
  /** No time at all: possibly away, and such a week can't be submitted anyway. */
  noTime: MissingTimesheet[];
}

export function groupMissing(people: MissingTimesheet[]): MissingGroups {
  return { late: people.filter((p) => p.totalMinutes > 0), noTime: people.filter((p) => p.totalMinutes <= 0) };
}

/** Managers show up in the list too, but nobody reminds themselves. */
export function canRemind(person: MissingTimesheet, myUserId: number | null): boolean {
  return person.userId !== myUserId && !person.remindedToday;
}

/** Who "Remind all" writes to: everybody late who can still be reminded. Weeks without time are left out. */
export function remindAllIds(people: MissingTimesheet[], myUserId: number | null): number[] {
  return groupMissing(people)
    .late.filter((p) => canRemind(p, myUserId))
    .map((p) => p.userId);
}

/** "12h 30m of 32h". */
export function loggedOfCapacity(person: Pick<MissingTimesheet, 'totalMinutes' | 'weeklyCapacityHours'>): string {
  return `${formatMinutes(person.totalMinutes)} of ${formatHours(person.weeklyCapacityHours)}`;
}

const SKIPPED: Record<ReminderSkip, (who: string, many: boolean) => string> = {
  ALREADY_REMINDED: (who, many) => `${who} ${many ? 'were' : 'was'} already reminded about this week today.`,
  DAILY_LIMIT: (who, many) => `${who} ${many ? 'have' : 'has'} had enough reminders for one day.`,
  NOT_MISSING: (who, many) => `${who} ${many ? 'are' : 'is'} no longer on the list.`,
  YOURSELF: () => "You can't remind yourself.",
};

/** What a reminder request did, in a sentence or two for the notice above the list. */
export function remindSummary(result: RemindResult, people: MissingTimesheet[]): string {
  const sentences: string[] = [];
  if (result.reminded.length) {
    sentences.push(`Reminded ${names(result.reminded, people)}.`);
  }
  for (const reason of Object.keys(SKIPPED) as ReminderSkip[]) {
    const ids = result.skipped.filter((s) => s.reason === reason).map((s) => s.userId);
    if (ids.length) {
      sentences.push(SKIPPED[reason](names(ids, people), ids.length > 1));
    }
  }
  return sentences.join(' ') || 'No reminders sent.';
}

function names(userIds: number[], people: MissingTimesheet[]): string {
  const known = userIds.map((id) => people.find((p) => p.userId === id)?.userName);
  if (known.length > 2 || known.some((name) => !name)) {
    return known.length === 1 ? '1 person' : `${known.length} people`;
  }
  return known.join(' and ');
}
