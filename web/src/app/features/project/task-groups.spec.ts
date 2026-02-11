import { Phase, Task } from '../../core/models';
import { groupTasks } from './task-groups';

function task(id: number, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `T${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, ...extra,
  };
}

describe('groupTasks', () => {
  const phases: Phase[] = [
    { id: 2, projectId: 1, name: 'Build', position: 1 },
    { id: 1, projectId: 1, name: 'Design', position: 0 },
  ];

  it('groups by phase in phase order with unphased tasks last', () => {
    const groups = groupTasks([task(1, { phaseId: 2 }), task(2), task(3, { phaseId: 1 })], phases);
    expect(groups.map((g) => g.phase?.name ?? 'none')).toEqual(['Design', 'Build', 'none']);
    expect(groups[2].rows.map((r) => r.task.id)).toEqual([2]);
  });

  it('puts subtasks straight after their parent', () => {
    const groups = groupTasks(
      [task(1, { phaseId: 1, startDate: '2026-03-05' }), task(2, { phaseId: 1, startDate: '2026-03-02' }), task(3, { parentId: 1, phaseId: 1 })],
      phases,
    );
    expect(groups[0].rows.map((r) => [r.task.id, r.depth])).toEqual([[2, 0], [1, 0], [3, 1]]);
  });

  it('keeps empty phases but drops an empty "no phase" group', () => {
    const groups = groupTasks([task(1, { phaseId: 1 })], phases);
    expect(groups).toHaveLength(2);
    expect(groups[1].rows).toHaveLength(0);
  });
});
