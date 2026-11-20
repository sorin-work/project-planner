// Things the browser remembers for one person (the rows of their timesheet, for example)
// are kept under keys that start with that person's id, so that the next person to sign
// in on the same browser does not get them, and so that signing out can remove them.

/** The start of every localStorage key that belongs to this user. */
export function userKeyPrefix(userId: number): string {
  return `pp.user.${userId}.`;
}

/** Removes everything this browser kept for that user. */
export function forgetUser(store: Pick<Storage, 'key' | 'length' | 'removeItem'>, userId: number): void {
  const prefix = userKeyPrefix(userId);
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const key = store.key(i);
    if (key?.startsWith(prefix)) {
      keys.push(key);
    }
  }
  keys.forEach((key) => store.removeItem(key));
}
