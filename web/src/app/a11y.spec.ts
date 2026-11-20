import { expectNoAxeViolations } from '../testing/axe';
import { renderPage as render } from '../testing/render-page';

/**
 * Every page of the app, rendered as a whole inside the shell with an API that
 * answers from fixtures, and checked with axe. A page that asks for something
 * the fixtures do not have fails, so that every page is checked with content.
 */
const PAGES = [
  '/',
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
