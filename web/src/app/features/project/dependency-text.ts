import { Dependency, DependencyType } from '../../core/models';

/** The four types in the words the task panel's select uses. */
export const DEPENDENCY_TYPES: { type: DependencyType; label: string }[] = [
  { type: 'FS', label: 'starts after it ends' },
  { type: 'SS', label: 'starts when it starts' },
  { type: 'FF', label: 'ends when it ends' },
  { type: 'SF', label: 'ends when it starts' },
];

function days(n: number): string {
  return `${n} working day${n === 1 ? '' : 's'}`;
}

/**
 * How this task waits for {@code predecessor}, as a phrase that follows the task's
 * title or "This": "starts 2 working days before Design ends", "ends with Build".
 * A negative lag is a lead.
 */
export function dependencyPhrase(dependency: Pick<Dependency, 'type' | 'lagDays'>, predecessor: string): string {
  const own = dependency.type === 'FS' || dependency.type === 'SS' ? 'starts' : 'ends';
  const theirs = dependency.type === 'FS' || dependency.type === 'FF' ? 'ends' : 'starts';
  const lag = dependency.lagDays;
  if (lag === 0) {
    switch (dependency.type) {
      case 'FS':
        return `starts after ${predecessor} ends`;
      case 'SS':
        return `starts with ${predecessor}`;
      case 'FF':
        return `ends with ${predecessor}`;
      case 'SF':
        return `ends when ${predecessor} starts`;
    }
  }
  if (dependency.type === 'FS') {
    // Finish-to-start counts from the first working day after the predecessor ends.
    return lag > 0
      ? `${own} ${days(lag)} after ${predecessor} ends`
      : `${own} ${days(-lag)} before ${predecessor} ends`;
  }
  return `${own} ${days(Math.abs(lag))} ${lag > 0 ? 'after' : 'before'} ${predecessor} ${theirs}`;
}

/** A short tag for an arrow on the timeline, or null for a plain finish-to-start link: "SS +2", "FS -1". */
export function linkTag(dependency: Pick<Dependency, 'type' | 'lagDays'>): string | null {
  if (dependency.type === 'FS' && dependency.lagDays === 0) {
    return null;
  }
  const lag = dependency.lagDays === 0 ? '' : ` ${dependency.lagDays > 0 ? '+' : '-'}${Math.abs(dependency.lagDays)}`;
  return `${dependency.type}${lag}`;
}
