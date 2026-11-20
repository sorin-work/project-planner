import { SearchHit } from '../../core/models';

const MAX_RECENT = 5;
const TYPES = ['PROJECT', 'TASK', 'COMMENT'];

/** Recent picks are kept per person and organisation: a browser can be shared, a list of titles should not be. */
export function recentKey(userId: number, orgId: number): string {
  return `pp.search.recent.${userId}.${orgId}`;
}

/** What is kept of a hit: enough to show it and to go there. Not the snippet, which is somebody's text. */
function kept(hit: SearchHit): SearchHit {
  const { type, id, projectId, projectCode, taskId, title } = hit;
  return taskId === undefined ? { type, id, projectId, projectCode, title } : { type, id, projectId, projectCode, taskId, title };
}

function isHit(value: unknown): value is SearchHit {
  const hit = value as Partial<SearchHit> | null;
  return (
    typeof hit === 'object' && hit !== null && TYPES.includes(hit.type as string) && Number.isInteger(hit.id) &&
    Number.isInteger(hit.projectId) && typeof hit.projectCode === 'string' && typeof hit.title === 'string' &&
    (hit.taskId === undefined || Number.isInteger(hit.taskId))
  );
}

/** The last picks, newest first. Empty when there are none or the storage cannot be read. */
export function loadRecent(storage: Pick<Storage, 'getItem'> | null, key: string): SearchHit[] {
  try {
    const stored: unknown = JSON.parse(storage?.getItem(key) ?? '[]');
    return Array.isArray(stored) ? stored.filter(isHit).slice(0, MAX_RECENT).map(kept) : [];
  } catch {
    return [];
  }
}

/** Puts a pick first, once, and keeps the last few. A storage that is full or switched off is not an error. */
export function rememberPick(storage: Pick<Storage, 'getItem' | 'setItem'> | null, key: string, hit: SearchHit): SearchHit[] {
  const others = loadRecent(storage, key).filter((old) => old.type !== hit.type || old.id !== hit.id);
  const recent = [kept(hit), ...others].slice(0, MAX_RECENT);
  try {
    storage?.setItem(key, JSON.stringify(recent));
  } catch {
    // Private windows and full disks: the pick still happened.
  }
  return recent;
}
