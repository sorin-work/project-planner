import { WeekLoad } from '../../core/models';

export type LoadLevel = 'empty' | 'light' | 'good' | 'full' | 'over';

/** How busy a week is relative to capacity, for colouring the workload grid. */
export function loadLevel(week: Pick<WeekLoad, 'allocatedHours' | 'capacityHours'>): LoadLevel {
  if (week.allocatedHours <= 0) {
    return 'empty';
  }
  if (week.capacityHours <= 0) {
    return 'over';
  }
  const ratio = week.allocatedHours / week.capacityHours;
  if (ratio > 1) {
    return 'over';
  }
  if (ratio >= 0.85) {
    return 'full';
  }
  if (ratio >= 0.5) {
    return 'good';
  }
  return 'light';
}
