import { SearchHit } from '../../core/models';
import { loadRecent, recentKey, rememberPick } from './recent-picks';

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

const task = (id: number, title = `Task ${id}`): SearchHit => ({ type: 'TASK', id, projectId: 4, projectCode: 'WEB', taskId: id, title });

describe('recent picks', () => {
  it('keeps them per person and organisation', () => {
    expect(recentKey(7, 3)).toBe('pp.search.recent.7.3');
    expect(recentKey(8, 3)).not.toBe(recentKey(7, 3));
  });

  it('puts the newest first and a repeated pick once', () => {
    const storage = memory();
    rememberPick(storage, 'k', task(1));
    rememberPick(storage, 'k', task(2));
    expect(rememberPick(storage, 'k', task(1)).map((h) => h.id)).toEqual([1, 2]);
    expect(loadRecent(storage, 'k').map((h) => h.id)).toEqual([1, 2]);
  });

  it('keeps five and does not keep the snippet', () => {
    const storage = memory();
    for (let id = 1; id <= 7; id++) {
      rememberPick(storage, 'k', { ...task(id), snippet: 'somebody wrote this' });
    }
    expect(loadRecent(storage, 'k').map((h) => h.id)).toEqual([7, 6, 5, 4, 3]);
    expect(storage.data['k']).not.toContain('somebody');
  });

  it('tells a project from a task with the same id', () => {
    const storage = memory();
    rememberPick(storage, 'k', task(1));
    const project: SearchHit = { type: 'PROJECT', id: 1, projectId: 1, projectCode: 'WEB', title: 'Website' };
    expect(rememberPick(storage, 'k', project)).toEqual([project, task(1)]);
  });

  it('reads nothing from a storage that is missing, broken or holds something else', () => {
    expect(loadRecent(null, 'k')).toEqual([]);
    expect(loadRecent(memory({ k: 'not json' }), 'k')).toEqual([]);
    expect(loadRecent(memory({ k: '{"a":1}' }), 'k')).toEqual([]);
    expect(loadRecent(memory({ k: JSON.stringify([{ type: 'TASK', id: 'x' }, task(2), null]) }), 'k')).toEqual([task(2)]);
    const throwing = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('full');
      },
    };
    expect(loadRecent(throwing, 'k')).toEqual([]);
    expect(rememberPick(throwing, 'k', task(1))).toEqual([task(1)]);
  });
});
