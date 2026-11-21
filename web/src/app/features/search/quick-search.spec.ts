import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { SearchHit, SearchResponse } from '../../core/models';
import { QuickSearch, hitsOf } from './quick-search';

const hit = (id: number, title: string): SearchHit => ({ type: 'TASK', id, projectId: 1, projectCode: 'WEB', taskId: id, title });
const answer = (query: string, ...tasks: SearchHit[]): SearchResponse => ({ query, projects: [], tasks, comments: [], more: [] });

describe('QuickSearch', () => {
  /** Every request that was made, with the text it asked for and whether it was cancelled. */
  let requests: { text: string; answer: Subject<SearchResponse>; cancelled: boolean }[];
  let picked: SearchHit[];
  let search: QuickSearch;

  beforeEach(() => {
    vi.useFakeTimers();
    requests = [];
    picked = [];
    search = new QuickSearch(
      (text) => {
        const request = { text, answer: new Subject<SearchResponse>(), cancelled: false };
        requests.push(request);
        return new Observable<SearchResponse>((subscriber) => {
          const inner = request.answer.subscribe(subscriber);
          return () => {
            request.cancelled = true;
            inner.unsubscribe();
          };
        });
      },
      (h) => picked.push(h),
      200,
    );
  });

  afterEach(() => vi.useRealTimers());

  it('asks once, a moment after the last keystroke', () => {
    search.type('h');
    search.type('ho');
    search.type('hom');
    expect(requests).toHaveLength(0);
    expect(search.waiting()).toBe(true);

    vi.advanceTimersByTime(200);
    expect(requests.map((r) => r.text)).toEqual(['hom']);

    requests[0].answer.next(answer('hom', hit(1, 'Homepage')));
    expect(search.waiting()).toBe(false);
    expect(hitsOf(search.results()).map((h) => h.title)).toEqual(['Homepage']);
  });

  it('does not ask for less than two characters and shows nothing for them', () => {
    search.type('ho');
    vi.advanceTimersByTime(200);
    requests[0].answer.next(answer('ho', hit(1, 'Homepage')));

    search.type('h');
    vi.advanceTimersByTime(200);
    expect(requests).toHaveLength(1);
    expect(search.results()).toBeNull();
    expect(search.waiting()).toBe(false);
  });

  it('drops the answer to earlier text and cancels its request', () => {
    search.type('home');
    vi.advanceTimersByTime(200);
    search.type('homework');
    expect(requests[0].cancelled).toBe(true);
    vi.advanceTimersByTime(200);

    // The first answer arrives late anyway, as it would from a server that does not know.
    requests[0].answer.next(answer('home', hit(1, 'Homepage')));
    expect(search.results()).toBeNull();
    expect(search.waiting()).toBe(true);

    requests[1].answer.next(answer('homework', hit(2, 'Homework')));
    expect(hitsOf(search.results()).map((h) => h.id)).toEqual([2]);
  });

  it('opens the highlighted row on Enter when the list is the answer to the text', () => {
    search.type('home');
    vi.advanceTimersByTime(200);
    requests[0].answer.next(answer('home', hit(1, 'Homepage'), hit(2, 'Home office')));

    search.enter(hit(2, 'Home office'));
    expect(picked.map((h) => h.id)).toEqual([2]);
  });

  it('waits on Enter for the answer to the text as typed, and never opens the stale row', () => {
    search.type('home');
    vi.advanceTimersByTime(200);
    requests[0].answer.next(answer('home', hit(1, 'Homepage')));

    // More text, and Enter before the debounce has run: the row on screen is for "home".
    search.type('homework');
    search.enter(hit(1, 'Homepage'));
    expect(picked).toEqual([]);
    // Enter does not wait for more keystrokes.
    expect(requests.map((r) => r.text)).toEqual(['home', 'homework']);

    requests[1].answer.next(answer('homework', hit(2, 'Homework'), hit(3, 'Homework club')));
    expect(picked.map((h) => h.id)).toEqual([2]);
  });

  it('opens nothing when the awaited answer is empty', () => {
    search.type('zzz');
    search.enter(undefined);
    requests[0].answer.next(answer('zzz'));
    expect(picked).toEqual([]);
    expect(hitsOf(search.results())).toEqual([]);

    // The Enter is used up: a later answer does not open anything by itself.
    search.type('zz');
    vi.advanceTimersByTime(200);
    requests[1].answer.next(answer('zz', hit(4, 'Buzz')));
    expect(picked).toEqual([]);
  });

  it('takes the Enter back when typing goes on', () => {
    search.type('home');
    search.enter(undefined);
    search.type('homer');
    vi.advanceTimersByTime(200);
    requests[1].answer.next(answer('homer', hit(5, 'Homer')));
    expect(picked).toEqual([]);
  });

  it('shows an error for the text as typed and opens nothing', () => {
    search.type('home');
    search.enter(undefined);
    requests[0].answer.error(new HttpErrorResponse({ status: 500, error: { message: 'Something went wrong on our side' } }));
    expect(search.error()).toBe('Something went wrong on our side');
    expect(search.waiting()).toBe(false);
    expect(picked).toEqual([]);
  });

  it('starts over on reset, with nothing on its way', () => {
    search.type('home');
    vi.advanceTimersByTime(200);
    search.reset();
    expect(requests[0].cancelled).toBe(true);
    expect(search.results()).toBeNull();
    expect(search.waiting()).toBe(false);

    search.type('home');
    vi.advanceTimersByTime(200);
    expect(requests).toHaveLength(2);
  });
});
