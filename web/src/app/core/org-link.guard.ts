import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';
import { Me } from './models';
import { OrgContext } from './org-context.service';

/**
 * The organisation a mailed link names with ?org=, when the person is a member of it, or null.
 * A link from a mail of the second organisation would otherwise open in the first one, where
 * the project "does not exist".
 */
export function orgOfLink(param: string | null, me: Me | null): number | null {
  if (!param || !/^\d{1,18}$/.test(param) || !me) {
    return null;
  }
  const id = Number(param);
  return me.organisations.some((o) => o.organisationId === id) ? id : null;
}

/** Switches to the organisation of a mailed link before the page loads. A link for another one is opened as it is. */
export const orgLinkGuard: CanActivateFn = (_route, state): boolean | Observable<boolean> => {
  const param = state.root.queryParamMap.get('org');
  if (!param) {
    return true;
  }
  const auth = inject(AuthService);
  const org = inject(OrgContext);
  const apply = (me: Me | null): boolean => {
    const id = orgOfLink(param, me);
    if (id !== null && id !== org.orgId()) {
      org.select(id);
    }
    return true;
  };
  const me = auth.me();
  if (me) {
    return apply(me);
  }
  return auth.loadMe().pipe(
    map((loaded) => apply(loaded)),
    catchError(() => of(true)),
  );
};
