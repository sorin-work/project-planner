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

/** Where text sits when neither its rule nor a container it is in sets a background. */
const PAGE_BACKGROUNDS = ['--surface', '--bg', '--surface-2'];

/**
 * Elements whose rule is not nested under its container's in the stylesheet, with the
 * containers the templates put them in. Their text is measured on each container's background.
 */
const CONTAINERS: Record<string, string[]> = {
  // The marks in a workload cell: over capacity, and less capacity than usual.
  '.flag': ['.load-light', '.load-good', '.load-full', '.load-over'],
  '.less': ['.load-light', '.load-good', '.load-full', '.load-over'],
};

function ownBackground(rule: Rule, vars: Record<string, string>, under: Rgba): Rgba | null {
  const value = rule.props['background-color'] ?? rule.props['background'];
  if (!value) {
    return null;
  }
  const color = resolve(value, vars);
  return color && color[3] > 0 ? over(color, under) : null;
}

/** The rules that apply in a theme: everything outside a colour-scheme query, and that theme's query. */
function rulesOf(theme: Theme): Rule[] {
  return RULES.filter((r) => !r.media?.includes('prefers-color-scheme') || (theme === 'dark') === r.media.includes('dark'));
}

/** The rule that sets a background for exactly this selector, the last one when there are several. */
function backgroundRule(rules: Rule[], selector: string): Rule | undefined {
  return rules.filter((r) => r.selector === selector && (r.props['background'] || r.props['background-color'])).at(-1);
}

/**
 * The backgrounds the text of {@code rule} can stand on: its own; or the nearest container
 * named in its selector that sets one (".load-over .flag" stands on ".load-over"); or the
 * containers listed in CONTAINERS; or every page background.
 */
function backgroundsOf(rule: Rule, rules: Rule[], vars: Record<string, string>): { where: string; color: Rgba }[] {
  const page = PAGE_BACKGROUNDS.map((name) => ({ where: name, color: resolve(`var(${name})`, vars)! }));
  const own = ownBackground(rule, vars, page[0].color);
  if (own) {
    return [{ where: 'its background', color: own }];
  }
  const parts = rule.selector.split(/\s*>\s*|\s+/).filter(Boolean);
  for (let i = parts.length - 2; i >= 0; i--) {
    const container = backgroundRule(rules, parts[i]) ?? backgroundRule(rules, parts.slice(0, i + 1).join(' '));
    const color = container && ownBackground(container, vars, page[0].color);
    if (color) {
      return [{ where: parts[i], color }];
    }
  }
  const listed = CONTAINERS[rule.selector];
  if (listed) {
    return listed.flatMap((selector) => {
      const container = backgroundRule(rules, selector);
      return page.flatMap((p) => {
        const color = container && ownBackground(container, vars, p.color);
        return color ? [{ where: `${selector} on ${p.where}`, color }] : [];
      });
    });
  }
  return page;
}

/**
 * The text colour of a rule: its own, or for a rule that only sets an opacity or a
 * background, the colour it inherits: that of the same selector without its last class
 * (".tick.minor" from ".tick"), or --text.
 */
function textColour(rule: Rule, rules: Rule[], vars: Record<string, string>): { value: string; color: Rgba } | null {
  const ownColour = rule.props['color'] ?? rule.props['fill'];
  const svgText = /\.(tick|month|bar-label|link-tag)\b/.test(rule.selector);
  let value = rule.props['color'] ?? (svgText ? rule.props['fill'] : undefined);
  if (!value && (rule.props['opacity'] || ownBackground(rule, vars, [255, 255, 255, 1]))) {
    // Drop states and classes from the end until a rule of the same element sets a colour:
    // "button.primary:hover:not(:disabled)" takes the colour of "button.primary".
    let base = rule.selector;
    let inherited: Rule | undefined;
    while (!inherited) {
      const shorter = base.replace(/(\.[\w-]+|:[\w-]+(\([^)]*\))?)$/, '');
      if (shorter === base || !shorter) {
        break;
      }
      base = shorter;
      inherited = rules.filter((r) => r.selector === base && (r.props['color'] || r.props['fill'])).at(-1);
    }
    value = inherited ? (inherited.props['color'] ?? inherited.props['fill']) : 'var(--text)';
  }
  if (!value || (!svgText && !rule.props['color'] && ownColour && !rule.props['opacity'] && !ownBackground(rule, vars, [255, 255, 255, 1]))) {
    return null;
  }
  const color = resolve(value, vars);
  if (!color) {
    return null;
  }
  // Opacity fades the text into whatever is behind it.
  const opacity = rule.props['opacity'] ? Number(rule.props['opacity']) : 1;
  return { value, color: [color[0], color[1], color[2], color[3] * opacity] };
}

/** A disabled state is exempt from contrast (WCAG 1.4.3); a state that excludes it, ":not(:disabled)", is not. */
function disabledState(selector: string): boolean {
  return /:disabled|\[disabled\]/.test(selector.replace(/:not\([^)]*\)/g, ''));
}

function failures(theme: Theme): string[] {
  const rules = rulesOf(theme);
  const vars = tokens(RULES, theme);
  const found: string[] = [];
  for (const rule of rules) {
    if (rule.selector === ':root' || NOT_TEXT[rule.selector] || disabledState(rule.selector)) {
      continue;
    }
    // In the timeline only the text elements are text; the rest are shapes (bars, dots,
    // weekend columns), which carry their meaning in their labels as well.
    if (rule.selector.startsWith('.gantt-svg') && !/\.(tick|month|bar-label|link-tag)\b/.test(rule.selector)) {
      continue;
    }
    const text = textColour(rule, rules, vars);
    if (!text) {
      continue;
    }
    for (const behind of backgroundsOf(rule, rules, vars)) {
      const r = ratio(over(text.color, behind.color), behind.color);
      if (r < 4.5) {
        found.push(`${theme}: "${rule.selector}" ${text.value} on ${behind.where}: ${r.toFixed(2)}`);
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

