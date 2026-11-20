import { HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../app/app.routes';
import { PageTitle } from '../app/core/page-title';
import { FakeRoute, fakeApi } from './fake-api';
import { FIXTURES } from './fixtures';

/**
 * Renders a page of the app as a whole, the shell and the router included, signed in as
 * the owner of the fixtures' organisation, with an API that answers from the fixtures.
 * {@code missed} lists the calls the fixtures had no answer for.
 */
export async function renderPage(url: string, loggedIn = true, sent: { method: string; url: string; body: unknown }[] = []): Promise<{ harness: RouterTestingHarness; missed: string[] }> {
  localStorage.clear();
  if (loggedIn) {
    localStorage.setItem('pp.token', 'token');
    localStorage.setItem('pp.expiresAt', '2999-01-01T00:00:00Z');
    localStorage.setItem('pp.orgId', '3');
  }
  const missed: string[] = [];
  const recorded: FakeRoute[] = FIXTURES.map((route) => ({
    ...route,
    body: (req: HttpRequest<unknown>) => {
      if (req.method !== 'GET') {
        sent.push({ method: req.method, url: req.url, body: req.body });
      }
      return typeof route.body === 'function' ? (route.body as (r: HttpRequest<unknown>) => unknown)(req) : route.body;
    },
  }));
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(withInterceptors([fakeApi(recorded, missed)])),
      { provide: TitleStrategy, useClass: PageTitle },
    ],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  await harness.fixture.whenStable();
  return { harness, missed };
}
