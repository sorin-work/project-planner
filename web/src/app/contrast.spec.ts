import { Rgba, Rule, Theme, over, parseRules, ratio, resolve, tokens } from '../testing/contrast';
import { AVATAR_COLORS } from './shared/avatar';

/**
 * Text against its background, for every rule of the stylesheet that sets a text colour,
 * hover and focus states included, in the light and the dark theme. A rule that sets its
 * own background is measured against it; one that does not against each background the
 * app puts text on. WCAG 2.2 AA: 4.5:1 for text, 3:1 for the focus ring and for SVG
 * shapes that carry meaning.
 */

interface Fs {
  readFileSync(path: string, encoding: string): string;
}
const fs = (globalThis as unknown as { process: { getBuiltinModule(id: string): Fs } }).process.getBuiltinModule('node:fs');
const RULES = parseRules(fs.readFileSync('src/styles.css', 'utf8'));
const THEMES: Theme[] = ['light', 'dark'];

/**
 * Rules whose text colour is left out, each with its reason. Everything else is measured.
 */
const NOT_TEXT: Record<string, string> = {
  '.avatar': 'white initials on a colour picked from the name, measured against every one of AVATAR_COLORS below',
  // Shapes that hold no text. What they show is also said in words next to them.
  '.timer-chip .dot': 'the pulsing dot of a running timer, aria-hidden; the chip says the time in text',
  '.bar-fill': 'the filled part of a progress bar; the share is written next to it and given as aria-valuenow',
  '.bar.over .bar-fill': 'the same, over budget',
  '.bar-marker': 'the planned-progress mark on a progress bar; the planned share is written next to it',
};

/** Where text sits when a rule does not set a background of its own. */
const PAGE_BACKGROUNDS = ['--surface', '--bg', '--surface-2'];

function background(rule: Rule, vars: Record<string, string>, under: Rgba): Rgba | null {
  const value = rule.props['background-color'] ?? rule.props['background'];
  if (!value) {
    return null;
  }
  const color = resolve(value, vars);
  return color ? over(color, under) : null;
}

function failures(theme: Theme): string[] {
  const vars = tokens(RULES, theme);
  const found: string[] = [];
  for (const rule of RULES) {
    if (rule.selector === ':root' || NOT_TEXT[rule.selector] || /:disabled|\[disabled\]/.test(rule.selector)) {
      continue;
    }
    if (rule.media?.includes('prefers-color-scheme: dark') && theme === 'light') {
      continue;
    }
    // A rule with a background and no text colour of its own holds the text it inherits, --text.
    const value = rule.props['color'] ?? (background(rule, vars, resolve('var(--surface)', vars)!) ? 'var(--text)' : undefined);
    const text = value ? resolve(value, vars) : null;
    if (!text) {
      continue;
    }
    const page = PAGE_BACKGROUNDS.map((name) => resolve(`var(${name})`, vars)!);
    const own = background(rule, vars, page[0]);
    for (const behind of own ? [own] : page) {
      const r = ratio(over(text, behind), behind);
      if (r < 4.5) {
        found.push(`${theme}: "${rule.selector}" ${value} on ${own ? 'its background' : 'the page'}: ${r.toFixed(2)}`);
      }
    }
  }
  return found;
}

describe('colour contrast from the stylesheet', () => {
  for (const theme of THEMES) {
    it(`keeps every text colour at 4.5:1 in the ${theme} theme`, () => {
      expect(failures(theme)).toEqual([]);
    });
  }

  it('keeps the initials of every avatar colour at 4.5:1', () => {
    const low = AVATAR_COLORS.filter((c) => ratio([255, 255, 255, 1], resolve(c, {})!) < 4.5);
    expect(low).toEqual([]);
  });

  for (const theme of THEMES) {
    it(`keeps the focus ring at 3:1 on every background in the ${theme} theme`, () => {
      const vars = tokens(RULES, theme);
      const ring = resolve('var(--primary)', vars)!;
      const low = PAGE_BACKGROUNDS.filter((name) => ratio(ring, resolve(`var(${name})`, vars)!) < 3);
      expect(low).toEqual([]);
    });

    it(`keeps the text drawn in the timeline at 4.5:1 in the ${theme} theme`, () => {
      const vars = tokens(RULES, theme);
      const surface = resolve('var(--surface)', vars)!;
      const header = resolve(RULES.find((r) => r.selector === '.gantt-svg .header-bg')!.props['fill'], vars)!;
      const pairs: [string, Rgba, Rgba][] = [
        // Month and day labels on the header band.
        ['month', resolve(RULES.find((r) => r.selector === '.gantt-svg .month')!.props['fill'], vars)!, header],
        ['tick', resolve(RULES.find((r) => r.selector === '.gantt-svg .tick')!.props['fill'], vars)!, header],
        // The name on a bar stands on its halo, which is the surface colour.
        ['bar label', resolve(RULES.find((r) => r.selector === '.gantt-svg .bar-label')!.props['fill'], vars)!, surface],
        ['link tag', resolve(RULES.find((r) => r.selector === '.gantt-svg .link-tag')!.props['fill'], vars)!, surface],
      ];
      expect(pairs.filter(([, text, back]) => ratio(text, back) < 4.5).map(([name, text, back]) => `${name}: ${ratio(text, back).toFixed(2)}`)).toEqual([]);
    });
  }
});

