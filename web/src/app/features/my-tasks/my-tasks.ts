import { SearchHit } from '../../core/models';
import { addDays, weekStart } from '../../shared/dates';

export type MyTaskGroupKey = 'overdue' | 'week' | 'later' | 'none' | 'done';

export interface MyTaskGroup {
  key: MyTaskGroupKey;
  label: string;
  tasks: SearchHit[];
}

const LABELS: Record<MyTaskGroupKey, string> = {
  overdue: 'Overdue',
  week: 'This week',
  later: 'Later',
  none: 'No date',
  done: 'Done',
};

const ORDER: MyTaskGroupKey[] = ['overdue', 'week', 'later', 'none', 'done'];

/**
 * Which group a task goes in, by the organisation's date: overdue before {@code today}, this
 * week up to its Sunday, later after that. Done tasks have a group of their own, whatever
 * their date, since a done task is never overdue.
 */
export function groupOf(task: SearchHit, today: string): MyTaskGroupKey {
  if (task.status === 'DONE') {
    return 'done';
  }
  if (!task.dueDate) {
    return 'none';
  }
  if (task.dueDate < today) {
    return 'overdue';
  }
  return task.dueDate <= addDays(weekStart(today), 6) ? 'week' : 'later';
}

/** The tasks in their groups, in the order they came in, leaving out the empty groups. */
export function groupMyTasks(tasks: SearchHit[], today: string): MyTaskGroup[] {
  const byKey = new Map<MyTaskGroupKey, SearchHit[]>();
  for (const task of tasks) {
    const key = groupOf(task, today);
    byKey.set(key, [...(byKey.get(key) ?? []), task]);
  }
  return ORDER.filter((key) => byKey.has(key)).map((key) => ({ key, label: LABELS[key], tasks: byKey.get(key)! }));
}
