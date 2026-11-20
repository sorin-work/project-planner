/** The parts of a key event that decide whether it is the search shortcut. */
export type ShortcutKey = Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey' | 'shiftKey' | 'repeat' | 'isComposing'>;

/** Macs, iPhones and iPads, going by what the browser says it runs on. */
export function isApplePlatform(platform: string): boolean {
  return /mac|iphone|ipad|ipod/i.test(platform);
}

/**
 * Ctrl+K, or Cmd+K on Apple platforms, where Ctrl+K deletes to the end of the line.
 * Only the one modifier: with Shift or Alt on top the combination belongs to the browser
 * (Ctrl+Shift+K is a developer console) or types a character (AltGr is Ctrl+Alt). A key
 * that is held down counts once.
 */
export function isSearchShortcut(event: ShortcutKey, apple: boolean): boolean {
  if (event.repeat || event.isComposing || event.altKey || event.shiftKey) {
    return false;
  }
  if (event.key !== 'k' && event.key !== 'K') {
    return false;
  }
  return apple ? event.metaKey && !event.ctrlKey : event.ctrlKey && !event.metaKey;
}

export function shortcutLabel(apple: boolean): string {
  return apple ? '⌘K' : 'Ctrl K';
}

/**
 * Listens for the shortcut on the whole page and calls `open` for it. Not while a dialog
 * is open: a second one on top would take the first one's focus away. The shortcut is
 * taken from the browser either way, so that it does the same thing everywhere in the app.
 * Returns the function that stops listening.
 */
export function listenForSearchShortcut(doc: Document, apple: boolean, open: () => void): () => void {
  const onKeydown = (event: KeyboardEvent) => {
    if (!isSearchShortcut(event, apple)) {
      return;
    }
    event.preventDefault();
    if (!doc.querySelector('dialog[open]')) {
      open();
    }
  };
  doc.addEventListener('keydown', onKeydown);
  return () => doc.removeEventListener('keydown', onKeydown);
}
