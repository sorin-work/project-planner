import { dropIndex, keyboardMove } from './board.tab';

describe('dropIndex', () => {
  it('drops above the first card whose middle is below the pointer', () => {
    expect(dropIndex([100, 160, 220], 90)).toBe(0);
    expect(dropIndex([100, 160, 220], 130)).toBe(1);
    expect(dropIndex([100, 160, 220], 400)).toBe(3);
    expect(dropIndex([], 50)).toBe(0);
  });
});

describe('keyboardMove', () => {
  const columns = [
    { status: 'TODO' as const, tasks: [{ id: 1 }, { id: 2 }] },
    { status: 'IN_PROGRESS' as const, tasks: [{ id: 3 }] },
    { status: 'IN_REVIEW' as const, tasks: [] },
    { status: 'DONE' as const, tasks: [{ id: 4 }] },
  ];

  it('changes the status with left and right, to the end of the next column', () => {
    expect(keyboardMove(columns, 1, 'ArrowRight')).toEqual({ status: 'IN_PROGRESS', position: 1 });
    expect(keyboardMove(columns, 3, 'ArrowRight')).toEqual({ status: 'IN_REVIEW', position: 0 });
    expect(keyboardMove(columns, 3, 'ArrowLeft')).toEqual({ status: 'TODO', position: 2 });
    expect(keyboardMove(columns, 1, 'ArrowLeft')).toBeNull();
    expect(keyboardMove(columns, 4, 'ArrowRight')).toBeNull();
  });

  it('changes the place with up and down, within the column', () => {
    expect(keyboardMove(columns, 2, 'ArrowUp')).toEqual({ status: 'TODO', position: 0 });
    expect(keyboardMove(columns, 1, 'ArrowDown')).toEqual({ status: 'TODO', position: 1 });
    expect(keyboardMove(columns, 1, 'ArrowUp')).toBeNull();
    expect(keyboardMove(columns, 2, 'ArrowDown')).toBeNull();
    expect(keyboardMove(columns, 9, 'ArrowDown')).toBeNull();
    expect(keyboardMove(columns, 1, 'Enter')).toBeNull();
  });
});
