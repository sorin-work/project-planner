import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { delay, of, throwError, timer } from 'rxjs';
import { mergeMap } from 'rxjs/operators';

/** A canned answer: a path pattern (after /api), optionally a method, and the body or a function of the request. */
export interface FakeRoute {
  method?: string;
  path: RegExp;
  body: unknown | ((req: HttpRequest<unknown>) => unknown);
}

/**
 * Answers API calls from a table instead of a server, for specs that render whole
 * pages. A call the table does not know is answered with 404 and written down in
 * {@code missed}, so that a spec can say which answers it lacks.
 */
export function fakeApi(routes: FakeRoute[], missed: string[] = []): HttpInterceptorFn {
  return (req) => {
    const path = req.url.replace(/^\/api/, '');
    const route = routes.find((r) => (r.method ?? 'GET') === req.method && r.path.test(path));
    if (!route) {
      missed.push(`${req.method} ${req.urlWithParams}`);
      return timer(0).pipe(mergeMap(() => throwError(() => new HttpErrorResponse({ status: 404, url: req.url, error: { status: 404, code: 'not_found', message: 'Not in the fake API' } }))));
    }
    const body = typeof route.body === 'function' ? (route.body as (r: HttpRequest<unknown>) => unknown)(req) : route.body;
    // Later, as a server answers: some pages ask again from what an answer changes, and an
    // answer in the same turn would never let them settle.
    return of(new HttpResponse({ status: req.method === 'POST' ? 201 : 200, body, url: req.url })).pipe(delay(0));
  };
}
