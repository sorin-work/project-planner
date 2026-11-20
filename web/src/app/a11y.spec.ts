import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { expectNoAxeViolations } from '../testing/axe';
import { fakeApi } from '../testing/fake-api';
import { routes } from './app.routes';
import { FIXTURES } from '../testing/fixtures';

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

async function render(url: string, loggedIn = true): Promise<{ harness: RouterTestingHarness; missed: string[] }> {
  localStorage.clear();
  if (loggedIn) {
    localStorage.setItem('pp.token', 'token');
    localStorage.setItem('pp.tokenExpiresAt', '2999-01-01T00:00:00Z');
    localStorage.setItem('pp.orgId', '3');
  }
  const missed: string[] = [];
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(withInterceptors([fakeApi(FIXTURES, missed)]))],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  await harness.fixture.whenStable();
  return { harness, missed };
}

describe('accessibility of every page', () => {
  afterEach(() => localStorage.clear());

  for (const url of PAGES) {
    it(`finds nothing wrong on ${url}`, async () => {
      const { harness, missed } = await render(url);
      expect(missed).toEqual([]);
      await expectNoAxeViolations(harness.fixture.nativeElement as HTMLElement);
    });
  }

  for (const url of ['/login', '/signup']) {
    it(`finds nothing wrong on ${url}`, async () => {
      const { harness, missed } = await render(url, false);
      expect(missed).toEqual([]);
      await expectNoAxeViolations(harness.fixture.nativeElement as HTMLElement);
    });
  }
});
