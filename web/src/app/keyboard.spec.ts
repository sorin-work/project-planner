import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { renderPage } from '../testing/render-page';
import { Announcer } from './core/announcer';

/** Waits for what the app does a moment later: focus moves, a title with the project's name. */
const later = (ms = 60) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * What a keyboard and a screen reader need from the whole app, on rendered pages: the
 * way past the navigation, the state of the controls, the title, the task panel's focus,
 * moving a card without a mouse, and the live regions that say what happened.
 */
describe('the app from the keyboard', () => {
  afterEach(() => localStorage.clear());

  it('skips past the navigation to the page, and says which page is the current one', async () => {
    const { harness } = await renderPage('/projects');
    const el = harness.fixture.nativeElement as HTMLElement;
    const skip = el.querySelector<HTMLAnchorElement>('a.skip-link')!;
    expect(el.querySelector('a, button')).toBe(skip);
    skip.click();
    expect(document.activeElement).toBe(el.querySelector('main#content'));

    const current = [...el.querySelectorAll('nav[aria-label="Main"] a[aria-current="page"]')].map((a) => a.textContent?.trim());
    expect(current).toEqual(['Projects']);
  });

  it('says whether the menu is open and how many notifications are unread', async () => {
    const { harness } = await renderPage('/');
    const el = harness.fixture.nativeElement as HTMLElement;
    const menu = el.querySelector<HTMLButtonElement>('.menu-toggle')!;
    expect(menu.getAttribute('aria-expanded')).toBe('false');
    expect(menu.getAttribute('aria-controls')).toBe('sidebar');
    menu.click();
    await harness.fixture.whenStable();
    expect(menu.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.bell')!.getAttribute('aria-label')).toBe('Notifications, 3 unread');
  });

  it('gives every page a title, with the name of the project on a project page', async () => {
    const { harness } = await renderPage('/time');
    expect(TestBed.inject(Title).getTitle()).toBe('Timesheet · project-planner');
    await harness.navigateByUrl('/projects/7/timeline');
    await harness.fixture.whenStable();
    await later();
    expect(TestBed.inject(Title).getTitle()).toBe('Timeline · Website · project-planner');
  });

  it('moves the focus into the task panel, closes it with Escape and gives the focus back to the row', async () => {
    const { harness } = await renderPage('/projects/7/list');
    const el = harness.fixture.nativeElement as HTMLElement;
    const row = el.querySelector<HTMLButtonElement>('button.task-title[data-task="2"]')!;
    row.focus();
    row.click();
    await harness.fixture.whenStable();
    await later();
    const panel = el.querySelector<HTMLElement>('aside.task-panel')!;
    expect(panel).not.toBeNull();
    expect(document.activeElement).toBe(panel);

    panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await harness.fixture.whenStable();
    await later();
    expect(el.querySelector('aside.task-panel')).toBeNull();
    expect((document.activeElement as HTMLElement).getAttribute('data-task')).toBe('2');
  });

  it('keeps a comment that is being written when Escape is pressed in it, and closes on the next Escape', async () => {
    const { harness } = await renderPage('/projects/7/list?task=2');
    const el = harness.fixture.nativeElement as HTMLElement;
    await later();
    const area = el.querySelector<HTMLTextAreaElement>('app-comment-box textarea')!;
    area.focus();
    area.value = 'Half a thought';
    area.dispatchEvent(new Event('input'));
    area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    expect(el.querySelector('aside.task-panel')).not.toBeNull();
    expect(area.value).toBe('Half a thought');
    expect(document.activeElement).toBe(el.querySelector('aside.task-panel'));
    expect(el.querySelector('aside.task-panel')!.textContent).toContain('Your comment is still here');

    el.querySelector('aside.task-panel')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    await later();
    expect(el.querySelector('aside.task-panel')).toBeNull();
  });

  it('puts the focus on the list and says so when the task that opened the panel is deleted', async () => {
    const { harness } = await renderPage('/projects/7/list');
    const el = harness.fixture.nativeElement as HTMLElement;
    const row = el.querySelector<HTMLButtonElement>('button.task-title[data-task="4"]')!;
    row.focus();
    row.click();
    await harness.fixture.whenStable();
    await later();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    [...el.querySelectorAll<HTMLButtonElement>('aside.task-panel button')].find((b) => b.textContent?.includes('Delete task'))!.click();
    await harness.fixture.whenStable();
    await later(150);
    expect(el.querySelector('aside.task-panel')).toBeNull();
    expect(document.activeElement).toBe(el.querySelector('.tab-content'));
    expect(TestBed.inject(Announcer).polite()).toBe('Deleted Launch.');
  });

  it('keeps Alt and an arrow key from leaving the page when a card cannot move, and says why', async () => {
    const { harness } = await renderPage('/projects/7/board');
    const el = harness.fixture.nativeElement as HTMLElement;
    const card = el.querySelector<HTMLElement>('[data-card="2"]')!;
    const back = new KeyboardEvent('keydown', { key: 'ArrowLeft', altKey: true, bubbles: true, cancelable: true });
    card.dispatchEvent(back);
    expect(back.defaultPrevented).toBe(true);
    await later();
    expect(TestBed.inject(Announcer).polite()).toBe('Build cannot go further left.');
  });

  it('tells a client viewer that cards are moved by the team', async () => {
    const viewer = { path: /^\/me$/, body: { user: { id: 9, email: 'v@example.com', name: 'Vic', handle: 'vic' },
      organisations: [{ organisationId: 3, organisationName: 'Brightline', role: 'CLIENT', currency: 'EUR' }] } };
    const sent: { method: string; url: string; body: unknown }[] = [];
    const { harness } = await renderPage('/projects/7/board', true, sent, [viewer]);
    const el = harness.fixture.nativeElement as HTMLElement;
    const forward = new KeyboardEvent('keydown', { key: 'ArrowRight', altKey: true, bubbles: true, cancelable: true });
    el.querySelector<HTMLElement>('[data-card="2"]')!.dispatchEvent(forward);
    expect(forward.defaultPrevented).toBe(true);
    await later();
    expect(TestBed.inject(Announcer).polite()).toBe('Only the team can move cards.');
    expect(sent).toEqual([]);
  });

  it('moves a board card with Alt and an arrow key, and says where it went', async () => {
    const sent: { method: string; url: string; body: unknown }[] = [];
    const { harness } = await renderPage('/projects/7/board', true, sent);
    const el = harness.fixture.nativeElement as HTMLElement;
    const card = el.querySelector<HTMLElement>('[data-card="2"]')!;
    expect(card.getAttribute('aria-describedby')).toBe('board-keys');
    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', altKey: true, bubbles: true }));
    await harness.fixture.whenStable();
    await later(120);
    expect(sent).toContainEqual({ method: 'PATCH', url: '/api/tasks/2', body: { status: 'IN_PROGRESS', position: 1 } });
    expect(TestBed.inject(Announcer).polite()).toBe('Build moved to In progress, place 2.');
    // Without Alt an arrow key does nothing to the card.
    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    expect(sent.filter((s) => s.method === 'PATCH')).toHaveLength(1);
  });

  it('reads out a move on the timeline made with the arrow keys', async () => {
    const sent: { method: string; url: string; body: unknown }[] = [];
    const { harness } = await renderPage('/projects/7/timeline', true, sent);
    const el = harness.fixture.nativeElement as HTMLElement;
    const svg = el.querySelector('svg.gantt-svg')!;
    expect(svg.getAttribute('role')).toBe('group');
    const bar = el.querySelector<SVGGElement>('g.bar[data-task="1"]')!;
    bar.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    // A move is sent after a short pause, in case the key is pressed again.
    await later(450);
    await harness.fixture.whenStable();
    await later(120);
    expect(sent.map((s) => `${s.method} ${s.url}`)).toContain('POST /api/tasks/1/shift');
    expect(TestBed.inject(Announcer).polite()).toMatch(/^Design: /);
  });
});

describe('Announcer', () => {
  it('reads out the same message twice by emptying the region first', async () => {
    const announcer = TestBed.inject(Announcer);
    announcer.announce('Saved.');
    await later();
    expect(announcer.polite()).toBe('Saved.');
    announcer.announce('Saved.');
    expect(announcer.polite()).toBe('');
    await later();
    expect(announcer.polite()).toBe('Saved.');
    announcer.alert('Not saved.');
    await later();
    expect(announcer.assertive()).toBe('Not saved.');
  });
});
