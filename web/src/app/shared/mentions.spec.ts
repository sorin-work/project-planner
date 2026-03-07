import { activeMention, insertMention, segments } from './mentions';

describe('mentions', () => {
  it('detects a mention being typed', () => {
    expect(activeMention('Hey @an', 7)).toEqual({ start: 4, query: 'an' });
    expect(activeMention('@', 1)).toEqual({ start: 0, query: '' });
    expect(activeMention('Hey there', 9)).toBeNull();
  });

  it('ignores email addresses', () => {
    expect(activeMention('mail ana@exa', 12)).toBeNull();
  });

  it('stops at whitespace', () => {
    expect(activeMention('@ana done', 9)).toBeNull();
  });

  it('inserts the chosen handle with a trailing space', () => {
    const text = 'Thanks @li for this';
    const mention = activeMention(text, 10)!;
    expect(insertMention(text, mention, 10, 'lena')).toEqual({ text: 'Thanks @lena for this', cursor: 13 });
  });

  it('splits text so known mentions can be highlighted', () => {
    const handles = new Set(['ana', 'ben']);
    expect(segments('@ana and @zed, ask @ben.', handles)).toEqual([
      { text: '@ana', mention: true },
      { text: ' and @zed, ask ', mention: false },
      { text: '@ben', mention: true },
      { text: '.', mention: false },
    ]);
    expect(segments('write to ana@example.com', handles)).toEqual([{ text: 'write to ana@example.com', mention: false }]);
  });
});
