import { expectNoAxeViolations } from '../testing/axe';
import { FakeRoute } from '../testing/fake-api';
import { ENTRY, ME } from '../testing/fixtures';
import { renderPage as render } from '../testing/render-page';

/**
 * Every page of the app, rendered as a whole inside the shell with an API that
 * answers from fixtures, and checked with axe. A page that asks for something
 * the fixtures do not have fails, so that every page is checked with content.
 */
const PAGES = [
  '/',
  '/my-tasks',
  '/projects',
  '/projects/7/overview',
  '/projects/7/list',
  '/projects/7/list?task=2',
  '/projects/7/board',
  '/projects/7/timeline',
  '/projects/7/activity',
  '/projects/7/settings',
  '/time',
  '/approvals',
  '/workload',
  '/clients',
  '/team',
  '/reports',
  '/notifications',
  '/settings',
];

describe('accessibility of every page', () => {
  afterEach(() => localStorage.clear());

  for (const url of PAGES) {
    it(`finds nothing wrong on ${url}`, async () => {
      const { harness, missed } = await render(url);
      expect(missed).toEqual([]);
      await expectNoAxeViolations(harness.fixture.nativeElement as HTMLElement);
    }, 20_000);
  }

  for (const url of ['/login', '/signup']) {
    it(`finds nothing wrong on ${url}`, async () => {
      const { harness, missed } = await render(url, false);
      expect(missed).toEqual([]);
      await expectNoAxeViolations(harness.fixture.nativeElement as HTMLElement);
    }, 20_000);
  }

});

/** Waits for what opens a moment after a click or a keystroke. */
const later = (ms = 80) => new Promise((resolve) => setTimeout(resolve, ms));

/** The same answer for /me, with another role in the organisation. */
function as(role: 'MEMBER' | 'CLIENT'): FakeRoute {
  return { path: /^\/me$/, body: { ...ME, organisations: [{ ...ME.organisations[0], role }] } };
}

describe('accessibility of the states that open, fail and change', () => {
  afterEach(() => localStorage.clear());

  it('finds nothing wrong with the search dialog open', async () => {
    const { harness } = await render('/');
    const el = harness.fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('.search-button')!.click();
    await harness.fixture.whenStable();
    expect(el.querySelector('dialog.search-dialog')!.hasAttribute('open')).toBe(true);
    await expectNoAxeViolations(el);
  }, 20_000);

  it('finds nothing wrong with the menu open and a timer running', async () => {
    const running = { ...ENTRY, running: true, startedAt: new Date(Date.now() - 600_000).toISOString() };
    const { harness } = await render('/', true, [], [{ path: /^\/orgs\/3\/timer$/, body: running }]);
    const el = harness.fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('.menu-toggle')!.click();
    await harness.fixture.whenStable();
    expect(el.querySelector('.timer-chip')).not.toBeNull();
    await expectNoAxeViolations(el);
  }, 20_000);

  it('finds nothing wrong with the mention suggestions open', async () => {
    const { harness } = await render('/projects/7/list?task=2');
    const el = harness.fixture.nativeElement as HTMLElement;
    const area = el.querySelector<HTMLTextAreaElement>('app-comment-box textarea')!;
    area.value = 'Thanks @';
    area.setSelectionRange(area.value.length, area.value.length);
    area.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    expect(el.querySelector('[role="listbox"]')).not.toBeNull();
    expect(el.querySelector('app-comment-box [role="combobox"]')!.getAttribute('aria-expanded')).toBe('true');
    await expectNoAxeViolations(el);
  }, 20_000);

  it('finds nothing wrong where a page could not be loaded', async () => {
    const failed: FakeRoute = { path: /^\/orgs\/3\/projects$/, error: { status: 503, code: 'unavailable', message: 'The server is busy.' } };
    const { harness } = await render('/projects', true, [], [failed]);
    await later();
    await harness.fixture.whenStable();
    const el = harness.fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('The server is busy.');
    await expectNoAxeViolations(el);
  }, 20_000);

  for (const role of ['MEMBER', 'CLIENT'] as const) {
    for (const url of ['/', '/projects/7/timeline', '/projects/7/list?task=2', '/projects/7/board']) {
      it(`finds nothing wrong on ${url} for a ${role.toLowerCase()}`, async () => {
        const { harness } = await render(url, true, [], [as(role)]);
        await expectNoAxeViolations(harness.fixture.nativeElement as HTMLElement);
      }, 20_000);
    }
  }
});

