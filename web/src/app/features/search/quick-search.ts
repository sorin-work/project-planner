import { signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { messageOf } from '../../core/errors';
import { SearchHit, SearchResponse } from '../../core/models';

export const MIN_SEARCH_LENGTH = 2;
const DEBOUNCE_MS = 180;

/** Every hit of an answer in the order it is shown: projects, tasks, comments. */
export function hitsOf(response: SearchResponse | null): SearchHit[] {
  return response ? [...response.projects, ...response.tasks, ...response.comments] : [];
}

/**
 * The typing side of the search dialog, without the dialog. It asks for hits a moment
 * after the last keystroke and keeps three promises:
 *
 * - what is shown is the answer to the text as it is now. An answer to earlier text is
 *   dropped when it arrives, and its request is cancelled;
 * - Enter opens a hit of the answer to the text as typed. Pressed before that answer is
 *   there, it waits for it and opens its first hit. It never opens a row that is still
 *   on screen from earlier text;
 * - typing on after Enter takes the Enter back.
 */
export class QuickSearch {
  /** The answer on screen, or null when there is nothing to show for the text. */
  readonly results = signal<SearchResponse | null>(null);
  /** An answer to the text as typed is on its way. */
  readonly waiting = signal(false);
  readonly error = signal<string | null>(null);

  private text = '';
  /** The text that `results` or `error` answers. */
  private answered = '';
  private timer: ReturnType<typeof setTimeout> | null = null;
  private request: Subscription | null = null;
  private enterPending = false;

  constructor(
    private readonly search: (text: string) => Observable<SearchResponse>,
    private readonly pick: (hit: SearchHit) => void,
    private readonly debounceMs = DEBOUNCE_MS,
  ) {}

  /** The text in the field changed. */
  type(value: string): void {
    const text = value.trim();
    if (text === this.text) {
      return;
    }
    this.text = text;
    this.enterPending = false;
    this.stop();
    if (text.length < MIN_SEARCH_LENGTH) {
      this.show(text, null, null);
      return;
    }
    if (text === this.answered) {
      // Typed back to what is on screen.
      this.waiting.set(false);
      return;
    }
    this.waiting.set(true);
    this.timer = setTimeout(() => this.send(), this.debounceMs);
  }

  /** Enter was pressed with `highlighted` being the row under the highlight, if there is one. */
  enter(highlighted: SearchHit | undefined): void {
    if (this.text.length < MIN_SEARCH_LENGTH) {
      return;
    }
    if (!this.waiting()) {
      if (highlighted) {
        this.pick(highlighted);
      }
      return;
    }
    this.enterPending = true;
    if (this.timer !== null) {
      // No point in waiting for more keystrokes now.
      this.send();
    }
  }

  /** Back to an empty field, with nothing on its way. */
  reset(): void {
    this.text = '';
    this.enterPending = false;
    this.stop();
    this.show('', null, null);
  }

  private send(): void {
    const text = this.text;
    this.stop();
    this.request = this.search(text).subscribe({
      next: (response) => {
        if (text !== this.text) {
          return;
        }
        this.show(text, response, null);
        const first = hitsOf(response)[0];
        if (this.enterPending && first) {
          this.pick(first);
        }
        this.enterPending = false;
      },
      error: (err: unknown) => {
        if (text === this.text) {
          this.show(text, null, messageOf(err));
          this.enterPending = false;
        }
      },
    });
  }

  private show(text: string, results: SearchResponse | null, error: string | null): void {
    this.answered = text;
    this.results.set(results);
    this.error.set(error);
    this.waiting.set(false);
  }

  private stop(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.request?.unsubscribe();
    this.request = null;
  }
}
