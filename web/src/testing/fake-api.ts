import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

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
      return throwError(() => new HttpErrorResponse({ status: 404, url: req.url, error: { status: 404, code: 'not_found', message: 'Not in the fake API' } }));
    }
    const body = typeof route.body === 'function' ? (route.body as (r: HttpRequest<unknown>) => unknown)(req) : route.body;
    return of(new HttpResponse({ status: req.method === 'POST' ? 201 : 200, body, url: req.url }));
  };
}
