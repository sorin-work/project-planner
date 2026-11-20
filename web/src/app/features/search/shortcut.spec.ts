import { ShortcutKey, isApplePlatform, isSearchShortcut, listenForSearchShortcut, shortcutLabel } from './shortcut';

function key(overrides: Partial<ShortcutKey>): ShortcutKey {
  return { key: 'k', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, repeat: false, isComposing: false, ...overrides };
}

describe('the search shortcut', () => {
  it('is Ctrl+K everywhere but on Apple platforms', () => {
    expect(isSearchShortcut(key({ ctrlKey: true }), false)).toBe(true);
    expect(isSearchShortcut(key({ ctrlKey: true, key: 'K' }), false)).toBe(true);
    expect(isSearchShortcut(key({ metaKey: true }), false)).toBe(false);
    expect(isSearchShortcut(key({}), false)).toBe(false);
    expect(isSearchShortcut(key({ ctrlKey: true, key: 'j' }), false)).toBe(false);
  });

  it('is Cmd+K on Apple platforms, where Ctrl+K edits text', () => {
    expect(isSearchShortcut(key({ metaKey: true }), true)).toBe(true);
    expect(isSearchShortcut(key({ ctrlKey: true }), true)).toBe(false);
    expect(isSearchShortcut(key({ ctrlKey: true, metaKey: true }), true)).toBe(false);
  });

  it('leaves combinations with Shift or Alt to the browser and the keyboard layout', () => {
    expect(isSearchShortcut(key({ ctrlKey: true, shiftKey: true }), false)).toBe(false);
    expect(isSearchShortcut(key({ ctrlKey: true, altKey: true }), false)).toBe(false);
    expect(isSearchShortcut(key({ metaKey: true, shiftKey: true }), true)).toBe(false);
    expect(isSearchShortcut(key({ ctrlKey: true, metaKey: true }), false)).toBe(false);
  });

  it('counts a key that is held down once, and not while a character is being composed', () => {
    expect(isSearchShortcut(key({ ctrlKey: true, repeat: true }), false)).toBe(false);
    expect(isSearchShortcut(key({ ctrlKey: true, isComposing: true }), false)).toBe(false);
  });

  it('knows Apple platforms and names the key accordingly', () => {
    expect(isApplePlatform('MacIntel')).toBe(true);
    expect(isApplePlatform('iPhone')).toBe(true);
    expect(isApplePlatform('Win32')).toBe(false);
    expect(isApplePlatform('Linux x86_64')).toBe(false);
    expect(shortcutLabel(true)).toBe('⌘K');
    expect(shortcutLabel(false)).toBe('Ctrl K');
  });
});

describe('listening for the search shortcut', () => {
  let opened: number;
  let stop: () => void;

  beforeEach(() => {
    opened = 0;
    stop = listenForSearchShortcut(document, false, () => opened++);
  });

  afterEach(() => {
    stop();
    document.body.replaceChildren();
  });

  function press(init: KeyboardEventInit): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: 'k', bubbles: true, cancelable: true, ...init });
    document.body.dispatchEvent(event);
    return event;
  }

  it('opens on the shortcut and takes the key from the browser', () => {
    const event = press({ ctrlKey: true });
    expect(opened).toBe(1);
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves every other key alone', () => {
    expect(press({}).defaultPrevented).toBe(false);
    expect(press({ ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false);
    expect(press({ ctrlKey: true, repeat: true }).defaultPrevented).toBe(false);
    expect(opened).toBe(0);
  });

  it('does not open over a dialog that is already open', () => {
    const dialog = document.createElement('dialog');
    dialog.setAttribute('open', '');
    document.body.append(dialog);

    const event = press({ ctrlKey: true });
    expect(opened).toBe(0);
    expect(event.defaultPrevented).toBe(true);

    dialog.removeAttribute('open');
    press({ ctrlKey: true });
    expect(opened).toBe(1);
  });

  it('stops listening when asked to', () => {
    stop();
    press({ ctrlKey: true });
    expect(opened).toBe(0);
  });
});
