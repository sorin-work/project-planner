import { Phase, Task } from '../../core/models';

export interface TaskGroup {
  phase: Phase | null;
  rows: { task: Task; depth: number }[];
}

/**
 * Groups top-level tasks by phase (in phase order, "no phase" last) with each
 * task's subtasks right after it. Within a group tasks are sorted by start date,
 * undated ones last.
 */
export function groupTasks(tasks: Task[], phases: Phase[]): TaskGroup[] {
  const children = new Map<number, Task[]>();
  for (const t of tasks) {
    if (t.parentId) {
      children.set(t.parentId, [...(children.get(t.parentId) ?? []), t]);
    }
  }
  const byDate = (a: Task, b: Task) =>
    (a.startDate ?? a.dueDate ?? '9999').localeCompare(b.startDate ?? b.dueDate ?? '9999') || a.id - b.id;

  const ordered = [...phases].sort((a, b) => a.position - b.position || a.id - b.id);
  const known = new Set(ordered.map((p) => p.id));
  const groups: TaskGroup[] = [...ordered, null].map((phase) => ({ phase, rows: [] }));
  const top = tasks.filter((t) => !t.parentId).sort(byDate);
  for (const task of top) {
    const index = task.phaseId && known.has(task.phaseId) ? ordered.findIndex((p) => p.id === task.phaseId) : ordered.length;
    groups[index].rows.push({ task, depth: 0 });
    for (const sub of (children.get(task.id) ?? []).sort(byDate)) {
      groups[index].rows.push({ task: sub, depth: 1 });
    }
  }
  return groups.filter((g) => g.phase !== null || g.rows.length > 0);
}
