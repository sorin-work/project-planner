import axe from 'axe-core';

/**
 * The axe rules that are switched off, each with its reason. Nothing else is: a
 * violation is fixed in the template, not by switching its rule off.
 */
export const RULES_OFF: Record<string, string> = {
  // jsdom lays nothing out and computes no colours, so axe cannot judge contrast here.
  // contrast.spec.ts computes it from the stylesheet for every pair the app uses.
  'color-contrast': 'no colours in jsdom; see contrast.spec.ts',
};

/** What axe finds wrong in {@code root}, one line per rule with the elements it found. */
export async function axeViolations(root: Element): Promise<string[]> {
  const result = await axe.run(root, {
    rules: Object.fromEntries(Object.keys(RULES_OFF).map((id) => [id, { enabled: false }])),
    resultTypes: ['violations'],
  });
  return result.violations.map(
    (v) => `${v.id} (${v.impact}): ${v.help} at ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`,
  );
}

/** Fails with the list of what axe found, so that the message says what to fix. */
export async function expectNoAxeViolations(root: Element): Promise<void> {
  expect(await axeViolations(root)).toEqual([]);
}
