import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { SearchHit, SearchResponse } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { recentKey } from './recent-picks';
import { SearchDialog, hitTarget } from './search-dialog';

const homepage: SearchHit = { type: 'TASK', id: 11, projectId: 4, projectCode: 'WEB', taskId: 11, title: 'Homepage' };
const website: SearchHit = { type: 'PROJECT', id: 4, projectId: 4, projectCode: 'WEB', title: 'Website', snippet: 'Northwind' };
const remark: SearchHit = { type: 'COMMENT', id: 90, projectId: 4, projectCode: 'WEB', taskId: 12, title: 'Footer', snippet: 'home link is broken' };
const answer: SearchResponse = { query: 'home', projects: [website], tasks: [homepage], comments: [remark], more: [] };

describe('hitTarget', () => {
  it('leads a project to its page and a task or a comment to the task in the list', () => {
    expect(hitTarget(website)).toEqual({ commands: ['/projects', 4] });
    expect(hitTarget(homepage)).toEqual({ commands: ['/projects', 4, 'list'], queryParams: { task: 11 } });
    expect(hitTarget(remark)).toEqual({ commands: ['/projects', 4, 'list'], queryParams: { task: 12 } });
  });
});

describe('SearchDialog', () => {
  let fixture: ComponentFixture<SearchDialog>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let opener: HTMLButtonElement;
  let orgId: number | null;

  const el = () => fixture.nativeElement as HTMLElement;
  const dialog = () => el().querySelector('dialog') as HTMLDialogElement;
  const field = () => el().querySelector('input') as HTMLInputElement;
  const options = () => [...el().querySelectorAll('[role="option"]')] as HTMLElement[];
  const titles = () => options().map((o) => o.querySelector('.search-title')?.textContent);

  async function type(text: string): Promise<void> {
    field().value = text;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function press(key: string, target: HTMLElement = field(), init: KeyboardEventInit = {}): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
    target.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  /** Lets the debounce run and answers the request it makes. */
  async function answerWith(text: string, response: SearchResponse): Promise<void> {
    vi.advanceTimersByTime(200);
    http.expectOne((r) => r.url === '/api/orgs/3/search' && r.params.get('q') === text).flush(response);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    vi.useFakeTimers();
    localStorage.clear();
    orgId = 3;
    TestBed.configureTestingModule({
      imports: [SearchDialog],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OrgContext, useValue: { orgId: () => orgId } },
        { provide: AuthService, useValue: { user: () => ({ id: 7 }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    opener = document.createElement('button');
    document.body.append(opener);
    opener.focus();
    fixture = TestBed.createComponent(SearchDialog);
    document.body.append(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
    opener.remove();
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('opens with the focus in a combobox that owns the list', async () => {
    fixture.componentInstance.open();
    fixture.detectChanges();

    expect(dialog().hasAttribute('open')).toBe(true);
    expect(document.activeElement).toBe(field());
    expect(field().getAttribute('role')).toBe('combobox');
    expect(field().getAttribute('aria-controls')).toBe('search-results');
    expect(el().querySelector('#search-results')?.getAttribute('role')).toBe('listbox');
    expect(field().getAttribute('aria-expanded')).toBe('false');
    expect(field().hasAttribute('aria-activedescendant')).toBe(false);
  });

  it('does not open before there is an organisation to search in', async () => {
    orgId = null;
    fixture.componentInstance.open();
    fixture.detectChanges();
    expect(dialog().hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(opener);
  });

  it('lists the hits by kind and tells screen readers which one the arrow keys are on', async () => {
    fixture.componentInstance.open();
    await type('home');
    expect(el().querySelector('.search-message')?.textContent).toBe('Searching…');
    await answerWith('home', answer);

    expect([...el().querySelectorAll('[role="group"]')].map((g) => g.getAttribute('aria-label'))).toEqual(['Projects', 'Tasks', 'Comments']);
    expect(titles()).toEqual(['Website', 'Homepage', 'Footer']);
    expect(field().getAttribute('aria-expanded')).toBe('true');
    expect(field().getAttribute('aria-activedescendant')).toBe(options()[0].id);
    expect(options().map((o) => o.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
    expect(el().querySelector('[role="status"]')?.textContent).toBe('3 results');

    const down = await press('ArrowDown');
    expect(down.defaultPrevented).toBe(true);
    expect(field().getAttribute('aria-activedescendant')).toBe(options()[1].id);
    expect(options()[1].getAttribute('aria-selected')).toBe('true');
    await press('ArrowUp');
    await press('ArrowUp');
    expect(field().getAttribute('aria-activedescendant')).toBe(options()[2].id);
    // The focus never leaves the field.
    expect(document.activeElement).toBe(field());
  });

  it('opens the highlighted hit on Enter, closes and gives the focus back', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', answer);
    await press('ArrowDown');
    await press('Enter');

    expect(navigate).toHaveBeenCalledWith(['/projects', 4, 'list'], { queryParams: { task: 11 } });
    expect(dialog().hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(opener);
  });

  it('waits on Enter for the answer to the text as typed instead of opening what is still on screen', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', answer);

    await type('homework');
    await press('Enter');
    expect(navigate).not.toHaveBeenCalled();

    const homework: SearchHit = { type: 'TASK', id: 20, projectId: 5, projectCode: 'EDU', taskId: 20, title: 'Homework' };
    // Enter did not wait for the debounce either.
    http.expectOne((r) => r.params.get('q') === 'homework').flush({ query: 'homework', projects: [], tasks: [homework], comments: [], more: [] });
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/projects', 5, 'list'], { queryParams: { task: 20 } });
  });

  it('says so when nothing is found, and when the text is too short', async () => {
    fixture.componentInstance.open();
    await type('h');
    expect(el().querySelector('.search-message')?.textContent).toBe('Type at least two characters.');

    await type('zzz');
    await answerWith('zzz', { query: 'zzz', projects: [], tasks: [], comments: [], more: [] });
    expect(el().querySelector('.search-message')?.textContent).toBe('Nothing found for "zzz".');
    expect(el().querySelector('[role="status"]')?.textContent).toBe('No results');
    await press('Enter');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('closes on Escape and gives the focus back to where it was', async () => {
    fixture.componentInstance.open();
    await type('home');
    const escape = await press('Escape');

    expect(escape.defaultPrevented).toBe(true);
    expect(dialog().hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(opener);
    // The request that was about to be made is not made.
    vi.advanceTimersByTime(500);
  });

  it('keeps Tab inside the dialog', async () => {
    fixture.componentInstance.open();
    fixture.detectChanges();
    const close = el().querySelector('button') as HTMLButtonElement;

    const tab = await press('Tab');
    expect(tab.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(close);
    await press('Tab', close);
    expect(document.activeElement).toBe(field());
    await press('Tab', field(), { shiftKey: true });
    expect(document.activeElement).toBe(close);
  });

  it('shows the last picks of this person, with the titles they have now', async () => {
    localStorage.setItem(recentKey(7, 3), JSON.stringify([{ type: 'TASK', id: 11 }, { type: 'TASK', id: 99 }]));
    localStorage.setItem(recentKey(8, 3), JSON.stringify([{ type: 'PROJECT', id: 4 }]));
    fixture.componentInstance.open();
    fixture.detectChanges();
    // Nothing is shown from storage alone: it holds no titles.
    expect(options()).toEqual([]);

    const request = http.expectOne('/api/orgs/3/search/recent');
    expect(request.request.body).toEqual({ picks: [{ type: 'TASK', id: 11 }, { type: 'TASK', id: 99 }] });
    // Task 99 is gone, or out of reach now, so the answer leaves it out.
    request.flush([{ ...homepage, title: 'Homepage, renamed' }]);
    fixture.detectChanges();

    expect(el().querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Recent');
    expect(titles()).toEqual(['Homepage, renamed']);
    expect(JSON.parse(localStorage.getItem(recentKey(7, 3)) ?? '[]')).toEqual([{ type: 'TASK', id: 11 }]);
    await press('Enter');
    expect(navigate).toHaveBeenCalledWith(['/projects', 4, 'list'], { queryParams: { task: 11 } });
  });

  it('drops recent picks that arrive after the dialog was closed', async () => {
    localStorage.setItem(recentKey(7, 3), JSON.stringify([{ type: 'TASK', id: 11 }]));
    fixture.componentInstance.open();
    fixture.componentInstance.close();
    http.expectOne('/api/orgs/3/search/recent').flush([homepage]);
    expect(JSON.parse(localStorage.getItem(recentKey(7, 3)) ?? '[]')).toEqual([{ type: 'TASK', id: 11 }]);
  });

  it('puts the highlight back on the first row when a new answer replaces the list', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', answer);
    await press('ArrowDown');
    await press('ArrowDown');
    expect(field().getAttribute('aria-activedescendant')).toBe(options()[2].id);

    await type('homep');
    await answerWith('homep', { query: 'homep', projects: [], tasks: [homepage], comments: [], more: [] });
    expect(field().getAttribute('aria-activedescendant')).toBe(options()[0].id);
    await press('Enter');
    expect(navigate).toHaveBeenCalledWith(['/projects', 4, 'list'], { queryParams: { task: 11 } });
  });

  it('says when a kind has more hits than it lists', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', { ...answer, more: ['TASK'] });
    const groups = [...dialog().querySelectorAll('[role=group]')];
    expect(groups.map((g) => g.getAttribute('aria-label'))).toEqual(['Projects', 'Tasks, the first 1 of more', 'Comments']);
    expect(groups[1].textContent).toContain('type more to narrow it down');
  });

  it('ignores a held Enter', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', answer);
    const held = await press('Enter', field(), { repeat: true });
    expect(held.defaultPrevented).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect(dialog().hasAttribute('open')).toBe(true);
  });

  it('remembers what was picked, for this person and organisation, by kind and id only', async () => {
    fixture.componentInstance.open();
    await type('home');
    await answerWith('home', answer);
    options()[0].dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    fixture.detectChanges();

    expect(navigate).toHaveBeenCalledWith(['/projects', 4], { queryParams: undefined });
    expect(JSON.parse(localStorage.getItem(recentKey(7, 3)) ?? '[]')).toEqual([{ type: 'PROJECT', id: 4 }]);
  });
});
