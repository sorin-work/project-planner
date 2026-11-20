import { moveHighlight } from './list-keys';

describe('moveHighlight', () => {
  it('moves down and up and wraps around', () => {
    expect(moveHighlight('ArrowDown', 0, 3)).toBe(1);
    expect(moveHighlight('ArrowDown', 2, 3)).toBe(0);
    expect(moveHighlight('ArrowUp', 0, 3)).toBe(2);
    expect(moveHighlight('ArrowUp', 2, 3)).toBe(1);
  });

  it('leaves other keys and empty lists alone', () => {
    expect(moveHighlight('Enter', 1, 3)).toBeNull();
    expect(moveHighlight('ArrowDown', 0, 0)).toBeNull();
  });
});
