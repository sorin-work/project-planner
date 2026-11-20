import { SearchHitType } from '../../core/models';
import { userKeyPrefix } from '../../core/user-storage';

const MAX_RECENT = 5;
const TYPES: SearchHitType[] = ['PROJECT', 'TASK', 'COMMENT'];

/** What is kept of a pick: its kind and its id. Titles are fetched fresh, with the rules of the day. */
export interface RecentPick {
  type: SearchHitType;
  id: number;
}

/**
 * Recent picks are kept per person and organisation, under the person's prefix, so that
 * signing out removes them along with everything else kept for that person.
 */
export function recentKey(userId: number, orgId: number): string {
  return `${userKeyPrefix(userId)}search.recent.${orgId}`;
}

function isPick(value: unknown): value is RecentPick {
  const pick = value as Partial<RecentPick> | null;
  return typeof pick === 'object' && pick !== null && TYPES.includes(pick.type as SearchHitType) && Number.isInteger(pick.id);
}

/** The last picks, newest first. Empty when there are none or the storage cannot be read. */
export function loadRecent(storage: Pick<Storage, 'getItem'> | null, key: string): RecentPick[] {
  try {
    const stored: unknown = JSON.parse(storage?.getItem(key) ?? '[]');
    return Array.isArray(stored) ? stored.filter(isPick).slice(0, MAX_RECENT).map(({ type, id }) => ({ type, id })) : [];
  } catch {
    return [];
  }
}

/** Replaces the kept picks. A storage that is full or switched off is not an error. */
export function saveRecent(storage: Pick<Storage, 'setItem'> | null, key: string, picks: RecentPick[]): RecentPick[] {
  const kept = picks.slice(0, MAX_RECENT).map(({ type, id }) => ({ type, id }));
  try {
    storage?.setItem(key, JSON.stringify(kept));
  } catch {
    // Private windows and full disks: the pick still happened.
  }
  return kept;
}

/** Puts a pick first, once, and keeps the last few. */
export function rememberPick(storage: Pick<Storage, 'getItem' | 'setItem'> | null, key: string, pick: RecentPick): RecentPick[] {
  const others = loadRecent(storage, key).filter((old) => old.type !== pick.type || old.id !== pick.id);
  return saveRecent(storage, key, [pick, ...others]);
}
