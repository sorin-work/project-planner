import { Observable } from 'rxjs';

/** A save, built when its turn comes. Null when there turns out to be nothing to send. */
export type Save<T> = (left: T | undefined) => Observable<T> | null;

/**
 * Runs one save at a time per key (a grid cell). A save that is asked for while another
 * one for the same key is still on its way waits for it, and only the latest one waiting
 * is kept: the ones in between were typed over.
 *
 * A save is a function that is called when its turn comes, with what the save before it
 * left behind (the value its request emitted). That is what keeps a slow first save from
 * being followed by a second "create": by then the entry exists, and the second save
 * updates it. A save that fails leaves things as they were.
 */
export class SaveQueue<T> {
  private readonly running = new Set<string>();
  private readonly waiting = new Map<string, Save<T>>();
  /** What the saves that ran for a key so far left behind; forgotten once the key is quiet. */
  private readonly left = new Map<string, T>();
  private idle: (() => void)[] = [];

  submit(key: string, save: Save<T>): void {
    if (this.running.has(key)) {
      this.waiting.set(key, save);
    } else {
      this.start(key, save);
    }
  }

  /** Calls {@code then} once no save is running or waiting any more; straight away when none is. */
  whenIdle(then: () => void): void {
    if (this.running.size === 0) {
      then();
    } else {
      this.idle.push(then);
    }
  }

  private start(key: string, save: Save<T>): void {
    const request = save(this.left.get(key));
    if (!request) {
      this.left.delete(key);
      return;
    }
    this.running.add(key);
    request.subscribe({
      next: (value) => this.left.set(key, value),
      error: () => this.finished(key),
      complete: () => this.finished(key),
    });
  }

  private finished(key: string): void {
    this.running.delete(key);
    const next = this.waiting.get(key);
    if (next) {
      this.waiting.delete(key);
      this.start(key, next);
    } else {
      this.left.delete(key);
    }
    if (this.running.size === 0) {
      const callbacks = this.idle;
      this.idle = [];
      callbacks.forEach((then) => then());
    }
  }
}

/**
 * A key for one create, random enough to be unique per person. The API returns the entry
 * it already made when a create comes again with the same key, which is what makes it safe
 * to send a create a second time after an answer that never arrived.
 */
export function newClientKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
