import { dropIndex } from './board.tab';

describe('dropIndex', () => {
  it('drops above the first card whose middle is below the pointer', () => {
    expect(dropIndex([100, 160, 220], 90)).toBe(0);
    expect(dropIndex([100, 160, 220], 130)).toBe(1);
    expect(dropIndex([100, 160, 220], 400)).toBe(3);
    expect(dropIndex([], 50)).toBe(0);
  });
});
