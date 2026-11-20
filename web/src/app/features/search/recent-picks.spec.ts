import { SearchHit } from '../../core/models';
import { forgetUser } from '../../core/user-storage';
import { loadRecent, recentKey, rememberPick, saveRecent } from './recent-picks';

function memory(initial: Record<string, string> = {}) {
  const data = { ...initial };
  return {
    data,
    getItem: (key: string) => data[key] ?? null,
    setItem: (key: string, value: string) => {
      data[key] = value;
    },
  };
}

const task = (id: number): SearchHit => ({ type: 'TASK', id, projectId: 4, projectCode: 'WEB', taskId: id, title: `Task ${id}`, snippet: 'somebody wrote this' });

describe('recent picks', () => {
  it('keeps them per person and organisation, where signing out removes them', () => {
    expect(recentKey(8, 3)).not.toBe(recentKey(7, 3));
    expect(recentKey(7, 4)).not.toBe(recentKey(7, 3));
    localStorage.clear();
    localStorage.setItem(recentKey(7, 3), '[]');
    localStorage.setItem(recentKey(8, 3), '[]');
    forgetUser(localStorage, 7);
    expect(localStorage.getItem(recentKey(7, 3))).toBeNull();
    expect(localStorage.getItem(recentKey(8, 3))).toBe('[]');
  });

  it('puts the newest first and a repeated pick once', () => {
    const storage = memory();
    rememberPick(storage, 'k', task(1));
    rememberPick(storage, 'k', task(2));
    expect(rememberPick(storage, 'k', task(1))).toEqual([{ type: 'TASK', id: 1 }, { type: 'TASK', id: 2 }]);
    expect(loadRecent(storage, 'k').map((p) => p.id)).toEqual([1, 2]);
  });

  it('keeps five, and only the kind and the id: no title, no snippet', () => {
    const storage = memory();
    for (let id = 1; id <= 7; id++) {
      rememberPick(storage, 'k', task(id));
    }
    expect(loadRecent(storage, 'k').map((p) => p.id)).toEqual([7, 6, 5, 4, 3]);
    expect(storage.data['k']).not.toContain('somebody');
    expect(storage.data['k']).not.toContain('Task');
  });

  it('tells a project from a task with the same id', () => {
    const storage = memory();
    rememberPick(storage, 'k', task(1));
    expect(rememberPick(storage, 'k', { type: 'PROJECT', id: 1 })).toEqual([{ type: 'PROJECT', id: 1 }, { type: 'TASK', id: 1 }]);
  });

  it('replaces the list with the picks that are still there', () => {
    const storage = memory();
    rememberPick(storage, 'k', task(1));
    rememberPick(storage, 'k', task(2));
    saveRecent(storage, 'k', [task(2)]);
    expect(loadRecent(storage, 'k')).toEqual([{ type: 'TASK', id: 2 }]);
  });

  it('reads nothing from a storage that is missing, broken or holds something else', () => {
    expect(loadRecent(null, 'k')).toEqual([]);
    expect(loadRecent(memory({ k: 'not json' }), 'k')).toEqual([]);
    expect(loadRecent(memory({ k: '{"a":1}' }), 'k')).toEqual([]);
    expect(loadRecent(memory({ k: JSON.stringify([{ type: 'TASK', id: 'x' }, task(2), null]) }), 'k')).toEqual([{ type: 'TASK', id: 2 }]);
    const throwing = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('full');
      },
    };
    expect(loadRecent(throwing, 'k')).toEqual([]);
    expect(rememberPick(throwing, 'k', task(1))).toEqual([{ type: 'TASK', id: 1 }]);
  });
});
