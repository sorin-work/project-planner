import { Directive, effect, inject, input } from '@angular/core';
import { Announcer } from '../core/announcer';

/**
 * Reads out the text it is given whenever that text changes and is not empty, for a
 * message that appears after a save ("Saved.", "Role rates saved."). A status element
 * that is only now added to the page is not read out by every screen reader, so the
 * message goes through the shell's live region as well.
 */
@Directive({ selector: '[appAnnounce]' })
export class Announce {
  readonly appAnnounce = input<string | null | undefined>();
  private readonly announcer = inject(Announcer);

  constructor() {
    effect(() => {
      const text = this.appAnnounce();
      if (text) {
        this.announcer.announce(text);
      }
    });
  }
}
