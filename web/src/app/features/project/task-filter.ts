import { Priority, Task, TaskStatus } from '../../core/models';
import { PRIORITIES, TASK_STATUSES } from '../../shared/format';

/** What the List tab is narrowed down to. Everything unset shows every task. */
export interface TaskFilter {
  text: string;
  /** A user id, 'none' for tasks nobody has, or null for any. */
  assignee: number | 'none' | null;
  status: TaskStatus | null;
  priority: Priority | null;
  hideDone: boolean;
  mineOnly: boolean;
}

export const NO_FILTER: TaskFilter = { text: '', assignee: null, status: null, priority: null, hideDone: false, mineOnly: false };

/** The filter as it stands in the address, so that it survives a reload and comes back with the back button. */
export function filterFromParams(params: { get(name: string): string | null }): TaskFilter {
  const assignee = params.get('assignee');
  const status = params.get('status') as TaskStatus | null;
  const priority = params.get('priority') as Priority | null;
  return {
    text: params.get('q') ?? '',
    assignee: assignee === 'none' ? 'none' : assignee !== null && /^\d+$/.test(assignee) ? Number(assignee) : null,
    status: status !== null && TASK_STATUSES.includes(status) ? status : null,
    priority: priority !== null && PRIORITIES.includes(priority) ? priority : null,
    hideDone: params.get('done') === 'hide',
    mineOnly: params.get('mine') === '1',
  };
}

/** The query parameters for a filter. Null takes a parameter out of the address. */
export function filterToParams(filter: TaskFilter): Record<string, string | null> {
  return {
    q: filter.text.trim() || null,
    assignee: filter.assignee === null ? null : String(filter.assignee),
    status: filter.status,
    priority: filter.priority,
    done: filter.hideDone ? 'hide' : null,
    mine: filter.mineOnly ? '1' : null,
  };
}

export function isFiltering(filter: TaskFilter): boolean {
  return Object.values(filterToParams(filter)).some((value) => value !== null);
}

export function matchesFilter(task: Task, filter: TaskFilter, me: number | undefined): boolean {
  const text = filter.text.trim().toLowerCase();
  return (
    (!text || task.title.toLowerCase().includes(text) || (task.description ?? '').toLowerCase().includes(text)) &&
    (filter.assignee === null || (filter.assignee === 'none' ? task.assigneeId === undefined : task.assigneeId === filter.assignee)) &&
    (filter.status === null || task.status === filter.status) &&
    (filter.priority === null || task.priority === filter.priority) &&
    (!filter.hideDone || task.status !== 'DONE') &&
    (!filter.mineOnly || (me !== undefined && task.assigneeId === me))
  );
}

/**
 * The rows of a group that pass the filter. Rows come as a task followed by its subtasks.
 * A task that does not pass stays when one of its subtasks does, marked as `context`, so
 * that the subtask is not shown without the task it belongs to.
 */
export function filterRows<T extends { task: Task; depth: number }>(
  rows: T[],
  filter: TaskFilter,
  me: number | undefined,
): (T & { context: boolean })[] {
  const kept: (T & { context: boolean })[] = [];
  let parent: T | null = null;
  let parentKept = false;
  for (const row of rows) {
    const passes = matchesFilter(row.task, filter, me);
    if (row.depth === 0) {
      parent = row;
      parentKept = passes;
      if (passes) {
        kept.push({ ...row, context: false });
      }
    } else if (passes) {
      if (parent && !parentKept) {
        kept.push({ ...parent, context: true });
        parentKept = true;
      }
      kept.push({ ...row, context: false });
    }
  }
  return kept;
}

/** Everyone who has a task, by name, for the assignee filter. */
export function assigneesOf(tasks: Task[]): { id: number; name: string }[] {
  const names = new Map<number, string>();
  for (const task of tasks) {
    if (task.assigneeId !== undefined) {
      names.set(task.assigneeId, task.assigneeName ?? `#${task.assigneeId}`);
    }
  }
  return [...names].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
}
