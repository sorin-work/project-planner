/**
 * Colour contrast worked out from the stylesheet, since jsdom computes no colours and
 * axe cannot judge it there. The stylesheet is read as text: the colour tokens of each
 * theme, then every rule with the text colour and the background it sets. A colour can be
 * a hex value, rgb(), a named colour used here, var() of a token, or color-mix() in srgb.
 */

export type Rgba = [number, number, number, number];
export type Theme = 'light' | 'dark';

export interface Rule {
  selector: string;
  /** The declarations of the rule, last one wins, in lower case. */
  props: Record<string, string>;
  /** The media query the rule is under, if any. */
  media: string | null;
}

/** The rules of a stylesheet, with @media blocks opened up. Comments are dropped. */
export function parseRules(css: string): Rule[] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules: Rule[] = [];
  let i = 0;
  const parseBlock = (end: number, media: string | null) => {
    while (i < end) {
      const open = text.indexOf('{', i);
      if (open === -1 || open >= end) {
        break;
      }
      const head = text.slice(i, open).trim();
      if (head.startsWith('@media')) {
        const close = matching(text, open);
        i = open + 1;
        parseBlock(close, head);
        i = close + 1;
        continue;
      }
      const close = matching(text, open);
      if (head.startsWith('@')) {
        i = close + 1;
        continue;
      }
      const props: Record<string, string> = {};
      for (const declaration of text.slice(open + 1, close).split(';')) {
        const colon = declaration.indexOf(':');
        if (colon > 0) {
          props[declaration.slice(0, colon).trim().toLowerCase()] = declaration.slice(colon + 1).replace('!important', '').trim();
        }
      }
      rules.push({ selector: head.replace(/\s+/g, ' '), props, media });
      i = close + 1;
    }
  };
  parseBlock(text.length, null);
  return rules;
}

function matching(text: string, open: number): number {
  let depth = 0;
  for (let j = open; j < text.length; j++) {
    if (text[j] === '{') {
      depth++;
    } else if (text[j] === '}') {
      depth--;
      if (depth === 0) {
        return j;
      }
    }
  }
  throw new Error('Unbalanced braces in the stylesheet');
}

/** The colour tokens of a theme: :root, and for dark the :root under prefers-color-scheme: dark on top. */
export function tokens(rules: Rule[], theme: Theme): Record<string, string> {
  const result: Record<string, string> = {};
  for (const rule of rules) {
    const dark = rule.media?.includes('prefers-color-scheme: dark') ?? false;
    if (rule.selector === ':root' && (!rule.media || (dark && theme === 'dark'))) {
      for (const [name, value] of Object.entries(rule.props)) {
        if (name.startsWith('--')) {
          result[name] = value;
        }
      }
    }
  }
  return result;
}

const NAMED: Record<string, Rgba> = {
  white: [255, 255, 255, 1],
  black: [0, 0, 0, 1],
  transparent: [0, 0, 0, 0],
};

/** A colour value as RGBA, or null when it is not a colour (inherit, currentColor, a gradient). */
export function resolve(value: string, vars: Record<string, string>): Rgba | null {
  const v = value.trim().toLowerCase();
  const variable = /^var\((--[\w-]+)(?:,\s*(.+))?\)$/.exec(v);
  if (variable) {
    const token = vars[variable[1]];
    return token !== undefined ? resolve(token, vars) : variable[2] ? resolve(variable[2], vars) : null;
  }
  if (NAMED[v]) {
    return NAMED[v];
  }
  if (/^#[0-9a-f]{3}$/.test(v)) {
    return [parseInt(v[1] + v[1], 16), parseInt(v[2] + v[2], 16), parseInt(v[3] + v[3], 16), 1];
  }
  if (/^#[0-9a-f]{6}([0-9a-f]{2})?$/.test(v)) {
    const alpha = v.length === 9 ? parseInt(v.slice(7, 9), 16) / 255 : 1;
    return [parseInt(v.slice(1, 3), 16), parseInt(v.slice(3, 5), 16), parseInt(v.slice(5, 7), 16), alpha];
  }
  const rgb = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[/,]\s*([\d.]+%?))?\s*\)$/.exec(v);
  if (rgb) {
    const a = rgb[4] === undefined ? 1 : rgb[4].endsWith('%') ? parseFloat(rgb[4]) / 100 : parseFloat(rgb[4]);
    return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3]), a];
  }
  const mix = /^color-mix\(in srgb,\s*(.+)\)$/.exec(v);
  if (mix) {
    const [first, second] = splitArgs(mix[1]);
    const a = /^(.*?)\s+([\d.]+)%$/.exec(first);
    const b = /^(.*?)\s+([\d.]+)%$/.exec(second);
    const colorA = resolve(a ? a[1] : first, vars);
    const colorB = resolve(b ? b[1] : second, vars);
    if (!colorA || !colorB) {
      return null;
    }
    const pa = a ? Number(a[2]) / 100 : b ? 1 - Number(b[2]) / 100 : 0.5;
    return mixColors(colorA, colorB, pa);
  }
  return null;
}

function splitArgs(text: string): [string, string] {
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '(') {
      depth++;
    } else if (text[i] === ')') {
      depth--;
    } else if (text[i] === ',' && depth === 0) {
      return [text.slice(0, i).trim(), text.slice(i + 1).trim()];
    }
  }
  throw new Error(`Not two colours: ${text}`);
}

/** color-mix in srgb, with premultiplied alpha as the CSS spec has it. */
function mixColors(a: Rgba, b: Rgba, pa: number): Rgba {
  const pb = 1 - pa;
  const alpha = a[3] * pa + b[3] * pb;
  if (alpha === 0) {
    return [0, 0, 0, 0];
  }
  const channel = (i: number) => (a[i] * a[3] * pa + b[i] * b[3] * pb) / alpha;
  return [channel(0), channel(1), channel(2), alpha];
}

/** {@code top} painted over {@code bottom}. */
export function over(top: Rgba, bottom: Rgba): Rgba {
  const a = top[3] + bottom[3] * (1 - top[3]);
  const channel = (i: number) => (top[i] * top[3] + bottom[i] * bottom[3] * (1 - top[3])) / (a || 1);
  return [channel(0), channel(1), channel(2), a];
}

function luminance([r, g, b]: Rgba): number {
  const linear = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

/** The WCAG contrast ratio of two opaque colours. */
export function ratio(a: Rgba, b: Rgba): number {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}
