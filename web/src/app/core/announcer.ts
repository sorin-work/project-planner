import { Injectable, signal } from '@angular/core';

/**
 * What a screen reader should hear about something that happened out of sight: a save,
 * a move, an error. The shell renders the two messages in live regions, one polite and
 * one assertive. A message that is the same as the one before is still read out: the
 * region is emptied first and filled again a moment later.
 */
@Injectable({ providedIn: 'root' })
export class Announcer {
  readonly polite = signal('');
  readonly assertive = signal('');
  private timers: ReturnType<typeof setTimeout>[] = [];

  /** Says {@code message} once the reader is done with what it is reading. */
  announce(message: string): void {
    this.set(this.polite, message);
  }

  /** Says {@code message} at once: for an error the user has to know about. */
  alert(message: string): void {
    this.set(this.assertive, message);
  }

  private set(region: typeof this.polite, message: string): void {
    region.set('');
    this.timers.push(setTimeout(() => region.set(message), 50));
  }
}
