import { Observable, Subject } from 'rxjs';
import { SaveQueue, newClientKey } from './save-queue';

describe('SaveQueue', () => {
  /** A save that records when it starts, and with what, and finishes when the test says so. */
  function slowSave(name: string, log: string[]) {
    const request = new Subject<string>();
    return {
      save: (left: string | undefined): Observable<string> => {
        log.push(left === undefined ? name : `${name} after ${left}`);
        return request;
      },
      succeed: (value: string) => {
        request.next(value);
        request.complete();
      },
      fail: () => request.error(new Error('offline')),
    };
  }

  it('runs one save per key at a time and keeps only the latest one waiting', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];
    const first = slowSave('first', log);
    const second = slowSave('second', log);
    const third = slowSave('third', log);

    queue.submit('cell', first.save);
    queue.submit('cell', second.save);
    queue.submit('cell', third.save);
    expect(log).toEqual(['first']);

    first.succeed('entry 7');
    // The third save is told what the first one left behind; the second never runs.
    expect(log).toEqual(['first', 'third after entry 7']);
    third.succeed('entry 7 again');
    expect(log).toEqual(['first', 'third after entry 7']);
  });

  it('carries on with the waiting save when the one before it fails, with nothing changed', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];
    const first = slowSave('first', log);
    const second = slowSave('second', log);
    const third = slowSave('third', log);
    const fourth = slowSave('fourth', log);

    queue.submit('cell', first.save);
    queue.submit('cell', second.save);
    first.fail();
    expect(log).toEqual(['first', 'second']);

    queue.submit('cell', third.save);
    second.succeed('entry 7');
    queue.submit('cell', fourth.save);
    third.fail();
    expect(log).toEqual(['first', 'second', 'third after entry 7', 'fourth after entry 7']);
  });

  it('starts afresh once a cell has gone quiet', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];
    const first = slowSave('first', log);

    queue.submit('cell', first.save);
    first.succeed('entry 7');
    queue.submit('cell', slowSave('later', log).save);

    expect(log).toEqual(['first', 'later']);
  });

  it('does not make one cell wait for another', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];

    queue.submit('monday', slowSave('monday', log).save);
    queue.submit('tuesday', slowSave('tuesday', log).save);

    expect(log).toEqual(['monday', 'tuesday']);
  });

  it('is free again after a save that had nothing to send', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];

    queue.submit('cell', () => null);
    queue.submit('cell', slowSave('next', log).save);

    expect(log).toEqual(['next']);
  });

  it('says when every save has finished, the waiting ones included', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];
    const first = slowSave('first', log);
    const second = slowSave('second', log);
    const other = slowSave('other', log);

    queue.whenIdle((saved) => log.push(`idle at once, saved ${saved}`));
    queue.submit('cell', first.save);
    queue.submit('cell', second.save);
    queue.submit('other', other.save);
    queue.whenIdle((saved) => log.push(`idle, saved ${saved}`));

    first.succeed('a');
    other.succeed('b');
    expect(log).toEqual(['idle at once, saved true', 'first', 'other', 'second after a']);
    second.succeed('c');
    expect(log).toEqual(['idle at once, saved true', 'first', 'other', 'second after a', 'idle, saved true']);
  });

  it('says so when one of the saves it waited for failed', () => {
    const queue = new SaveQueue<string>();
    const log: string[] = [];
    const friday = slowSave('friday', log);
    const thursday = slowSave('thursday', log);

    queue.submit('friday', friday.save);
    queue.submit('thursday', thursday.save);
    queue.whenIdle((saved) => log.push(`first wait, saved ${saved}`));
    friday.fail();
    thursday.succeed('entry 8');
    expect(log).toEqual(['friday', 'thursday', 'first wait, saved false']);

    // A failure is only held against those who were waiting when it happened.
    queue.whenIdle((saved) => log.push(`afterwards, saved ${saved}`));
    expect(log.at(-1)).toBe('afterwards, saved true');
  });
});

describe('newClientKey', () => {
  it('makes a key the API accepts, and another one every time', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newClientKey()));
    expect(keys.size).toBe(50);
    for (const key of keys) {
      expect(key).toMatch(/^[0-9a-f]{32}$/);
    }
  });
});
