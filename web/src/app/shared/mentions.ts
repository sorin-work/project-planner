// @mentions in comments: "@" followed by someone's handle (the part of their email
// before the @). Mirrors MentionParser on the server.

const HANDLE_CHAR = /[\p{L}\p{N}._+-]/u;

export interface ActiveMention {
  /** Index of the "@". */
  start: number;
  /** What has been typed after the "@" so far. */
  query: string;
}

/** The mention being typed at {@code cursor}, if any, for showing suggestions. */
export function activeMention(text: string, cursor: number): ActiveMention | null {
  let i = cursor - 1;
  while (i >= 0 && HANDLE_CHAR.test(text[i])) {
    i--;
  }
  if (i < 0 || text[i] !== '@') {
    return null;
  }
  return { start: i, query: text.slice(i + 1, cursor).toLowerCase() };
}

/** Replaces the mention being typed with the full handle and a trailing space. */
export function insertMention(
  text: string,
  mention: ActiveMention,
  cursor: number,
  handle: string,
): { text: string; cursor: number } {
  const before = text.slice(0, mention.start);
  const after = text.slice(cursor);
  const inserted = `@${handle} `;
  return { text: before + inserted + after.replace(/^ /, ''), cursor: before.length + inserted.length };
}

export interface Segment {
  text: string;
  mention: boolean;
}

/** Splits comment text so mentions of known handles can be highlighted. */
export function segments(text: string, handles: Set<string>): Segment[] {
  const result: Segment[] = [];
  const pattern = /(^|[^\p{L}\p{N}._%+-])@([\p{L}\p{N}][\p{L}\p{N}._+-]*)/gu;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    let handle = match[2];
    while (handle.endsWith('.') || handle.endsWith('-')) {
      handle = handle.slice(0, -1);
    }
    if (!handles.has(handle.toLowerCase())) {
      continue;
    }
    const at = (match.index ?? 0) + match[1].length;
    if (at > last) {
      result.push({ text: text.slice(last, at), mention: false });
    }
    result.push({ text: `@${handle}`, mention: true });
    last = at + 1 + handle.length;
  }
  if (last < text.length) {
    result.push({ text: text.slice(last), mention: false });
  }
  return result;
}
