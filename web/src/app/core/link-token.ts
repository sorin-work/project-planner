/**
 * The token of a mailed link, from the part of the address after the #. It travels there
 * because a browser never sends that part to a server, so it stays out of access logs.
 */
export function tokenFromFragment(fragment: string | null | undefined): string | null {
  if (!fragment) {
    return null;
  }
  const token = new URLSearchParams(fragment.replace(/^#/, '')).get('token');
  return token && /^[A-Za-z0-9_-]{16,100}$/.test(token) ? token : null;
}

/** The token of a stop link in a notification mail: two base64url parts with a dot between. */
export function signedTokenFromFragment(fragment: string | null | undefined): string | null {
  if (!fragment) {
    return null;
  }
  const token = new URLSearchParams(fragment.replace(/^#/, '')).get('token');
  return token && /^[A-Za-z0-9_-]{8,120}\.[A-Za-z0-9_-]{16,80}$/.test(token) ? token : null;
}
