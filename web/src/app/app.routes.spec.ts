import { DefaultUrlSerializer, Route } from '@angular/router';
import { routes } from './app.routes';

/** The pages the API puts into mails, with the organisation, as in NotificationMailer and DailySummaries. */
const MAILED = ['/my-tasks?org=7', '/approvals?org=7', '/time?week=2026-03-02&org=7', '/notifications?org=7', '/projects/4/overview?org=7'];

/** The route a path lands on among the signed-in pages, or null when only the catch-all takes it. */
function landsOn(url: string): string | null {
  const path = new DefaultUrlSerializer().parse(url).root.children['primary']?.segments.map((s) => s.path) ?? [];
  const shell = routes.find((r) => r.path === '' && r.children);
  const match = (shell?.children ?? []).find((r: Route) => {
    const parts = (r.path ?? '').split('/').filter(Boolean);
    return parts.length > 0 && parts.length <= path.length && parts.every((p, i) => p.startsWith(':') || p === path[i]);
  });
  return match?.path ?? null;
}

describe('mailed links', () => {
  it('open a page of the app, and not the catch-all, with org= kept for the guard', () => {
    for (const url of MAILED) {
      expect(landsOn(url), url).not.toBeNull();
      expect(new DefaultUrlSerializer().parse(url).queryParamMap.get('org')).toBe('7');
    }
  });
});
