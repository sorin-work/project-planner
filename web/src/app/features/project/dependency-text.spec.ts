import { dependencyPhrase, linkTag } from './dependency-text';

describe('dependency text', () => {
  it('says each type without a lag', () => {
    expect(dependencyPhrase({ type: 'FS', lagDays: 0 }, 'Design')).toBe('starts after Design ends');
    expect(dependencyPhrase({ type: 'SS', lagDays: 0 }, 'Design')).toBe('starts with Design');
    expect(dependencyPhrase({ type: 'FF', lagDays: 0 }, 'Design')).toBe('ends with Design');
    expect(dependencyPhrase({ type: 'SF', lagDays: 0 }, 'Design')).toBe('ends before Design starts');
  });

  it('says a lag as after and a lead as before, in working days', () => {
    expect(dependencyPhrase({ type: 'FS', lagDays: 2 }, 'Design')).toBe('starts 2 working days after Design ends');
    expect(dependencyPhrase({ type: 'FS', lagDays: -1 }, 'Development')).toBe('starts 1 working day before Development ends');
    expect(dependencyPhrase({ type: 'SS', lagDays: 3 }, 'Design')).toBe('starts 3 working days after Design starts');
    expect(dependencyPhrase({ type: 'FF', lagDays: -2 }, 'Build')).toBe('ends 2 working days before Build ends');
    expect(dependencyPhrase({ type: 'SF', lagDays: 1 }, 'Build')).toBe('ends 1 working day after Build starts');
  });

  it('tags an arrow only when it is not a plain finish-to-start link', () => {
    expect(linkTag({ type: 'FS', lagDays: 0 })).toBeNull();
    expect(linkTag({ type: 'FS', lagDays: -2 })).toBe('FS -2');
    expect(linkTag({ type: 'SS', lagDays: 0 })).toBe('SS');
    expect(linkTag({ type: 'FF', lagDays: 3 })).toBe('FF +3');
  });
});
