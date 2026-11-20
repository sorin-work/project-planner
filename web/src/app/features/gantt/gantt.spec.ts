import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Task } from '../../core/models';
import { Gantt } from './gantt';
import { Zoom } from './gantt-layout';

function task(id: number, startDate: string, dueDate: string, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 1, title: `Task ${id}`, status: 'TODO', priority: 'MEDIUM', milestone: false, position: 0,
    createdAt: '', updatedAt: '', predecessors: [], subtaskCount: 0, loggedHours: 0, startDate, dueDate, ...extra,
  };
}

// 2026-03-02 is a Monday. The chart starts on Monday 23 February, and at day zoom a day is 28 pixels wide:
// Task 1 (Mon 2 - Thu 5) covers x 196 to 308 in the first row, Task 2 (Mon 9 - Tue 10) x 392 to 448 in the
// second, the launch milestone sits on Friday 13 in the third and Task 4 (Thu 19 - Fri 20) covers x 672 to 728
// in the fourth. Rows are 32 pixels high below a 44 pixel header.
const tasks = [
  task(1, '2026-03-02', '2026-03-05'),
  task(2, '2026-03-09', '2026-03-10', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }] }),
  task(3, '2026-03-13', '2026-03-13', { milestone: true }),
  task(4, '2026-03-19', '2026-03-20', { predecessors: [{ id: 51, predecessorId: 2, successorId: 4, lagDays: 0 }] }),
];

describe('Gantt editing', () => {
  let fixture: ComponentFixture<Gantt>;
  let el: HTMLElement;
  const moved = vi.fn();
  const resized = vi.fn();
  const extended = vi.fn();
  const linked = vi.fn();
  const unlinked = vi.fn();
  const opened = vi.fn();

  async function render(editable = true, zoom: Zoom = 'day'): Promise<void> {
    vi.clearAllMocks();
    fixture = TestBed.createComponent(Gantt);
    fixture.componentRef.setInput('tasks', tasks);
    fixture.componentRef.setInput('editable', editable);
    fixture.componentRef.setInput('zoom', zoom);
    fixture.componentInstance.moved.subscribe(moved);
    fixture.componentInstance.resized.subscribe(resized);
    fixture.componentInstance.extended.subscribe(extended);
    fixture.componentInstance.linked.subscribe(linked);
    fixture.componentInstance.unlinked.subscribe(unlinked);
    fixture.componentInstance.opened.subscribe(opened);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  }

  const bar = (index: number) => el.querySelectorAll<SVGGElement>('g.bar')[index];
  const body = (index: number) => bar(index).querySelector('.bar-bg, .milestone')!;
  const handle = (index: number, edge: 'start' | 'end') => bar(index).querySelectorAll('.handle')[edge === 'start' ? 0 : 1];
  const connector = (index: number) => bar(index).querySelector('.connector')!;
  const arrowFocus = (dependencyId: number) => el.querySelector<SVGElement>(`.link-focus[data-link="${dependencyId}"]`)!;

  function pointer(target: Element, type: string, clientX: number, clientY = 60): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, button: 0, pointerId: 1, clientX, clientY }));
  }

  /** Presses on {@code target}, moves there and lets go, the way a captured pointer reports it. */
  async function drag(target: Element, fromX: number, toX: number, toY = 60): Promise<void> {
    pointer(target, 'pointerdown', fromX);
    pointer(target, 'pointermove', toX, toY);
    await fixture.whenStable();
    pointer(target, 'pointerup', toX, toY);
    await fixture.whenStable();
  }

  function key(target: EventTarget, name: string, shiftKey = false): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: name, shiftKey, bubbles: true, cancelable: true }));
  }

  it('moves a bar by working days: three columns from Thursday is Monday, two working days', async () => {
    await render();
    await drag(body(3), 690, 690 + 3 * 28);
    expect(moved).toHaveBeenCalledWith({ taskId: 4, days: 2 });

    // Task 2 starts on a Monday. Two columns to the left is Saturday, so it goes to Friday: one working day.
    await drag(body(1), 400, 400 - 2 * 28);
    expect(moved).toHaveBeenLastCalledWith({ taskId: 2, days: -1 });
    expect(moved).toHaveBeenCalledTimes(2);
    expect(resized).not.toHaveBeenCalled();
    expect(opened).not.toHaveBeenCalled();
  });

  it('draws a dragged bar where it will land, as long as it will be', async () => {
    await render();
    pointer(body(0), 'pointerdown', 250);
    pointer(body(0), 'pointermove', 250 + 2 * 28);
    await fixture.whenStable();
    // Monday to Thursday, two columns on: Wednesday to Monday. The weekend makes the bar six columns wide.
    expect(body(0).getAttribute('x')).toBe(String(196 + 2 * 28 + 1));
    expect(body(0).getAttribute('width')).toBe(String(6 * 28 - 2));
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Move by +2 working days');
    pointer(body(0), 'pointerup', 250 + 2 * 28);
    await fixture.whenStable();
    expect(body(0).getAttribute('x')).toBe('197');
    expect(body(0).getAttribute('width')).toBe(String(4 * 28 - 2));
    expect(el.querySelector('.gantt-hint')).toBeNull();
  });

  it('opens the task on a press that stays put, even when it wobbles', async () => {
    await render();
    await drag(body(0), 250, 251);
    expect(opened).toHaveBeenCalledWith(1);
    await drag(handle(0, 'end'), 306, 303);
    expect(opened).toHaveBeenCalledTimes(2);
    expect(moved).not.toHaveBeenCalled();
    expect(resized).not.toHaveBeenCalled();
  });

  it('treats a press that went away and came back as a drag that changed nothing', async () => {
    await render();
    pointer(body(0), 'pointerdown', 250);
    pointer(body(0), 'pointermove', 290);
    pointer(body(0), 'pointermove', 251);
    pointer(body(0), 'pointerup', 251);
    await fixture.whenStable();
    expect(opened).not.toHaveBeenCalled();
    expect(moved).not.toHaveBeenCalled();
  });

  it('gives up a drag on Escape and puts the bar back', async () => {
    await render();
    pointer(handle(0, 'end'), 'pointerdown', 306);
    pointer(handle(0, 'end'), 'pointermove', 306 + 56);
    await fixture.whenStable();
    expect(body(0).getAttribute('width')).toBe(String(8 * 28 - 2));

    key(document, 'Escape');
    await fixture.whenStable();
    expect(body(0).getAttribute('width')).toBe(String(4 * 28 - 2));
    expect(el.querySelector('.gantt-hint')).toBeNull();
    pointer(handle(0, 'end'), 'pointerup', 306 + 56);
    await fixture.whenStable();
    expect(resized).not.toHaveBeenCalled();
    expect(opened).not.toHaveBeenCalled();
  });

  it('resizes from the end handle and snaps past the weekend', async () => {
    await render();
    await drag(handle(0, 'end'), 306, 306 + 28);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, dueDate: '2026-03-06' });
    await drag(handle(0, 'end'), 306, 306 + 2 * 28);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, dueDate: '2026-03-09' });
    expect(moved).not.toHaveBeenCalled();
  });

  it('resizes from the start handle and stops at the due date', async () => {
    await render();
    await drag(handle(0, 'start'), 198, 198 + 28);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, startDate: '2026-03-03' });
    await drag(handle(0, 'start'), 198, 198 + 10 * 28);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, startDate: '2026-03-05' });
  });

  it('moves with the arrow keys and asks for a due date step with Shift, once per press', async () => {
    await render();
    key(bar(0), 'ArrowRight');
    expect(moved).toHaveBeenLastCalledWith({ taskId: 1, days: 1 });
    key(bar(0), 'ArrowLeft');
    expect(moved).toHaveBeenLastCalledWith({ taskId: 1, days: -1 });

    // The tasks do not change between the presses, as when the API has not answered yet.
    key(bar(0), 'ArrowRight', true);
    key(bar(0), 'ArrowRight', true);
    key(bar(0), 'ArrowLeft', true);
    expect(extended.mock.calls).toEqual([[{ taskId: 1, days: 1 }], [{ taskId: 1, days: 1 }], [{ taskId: 1, days: -1 }]]);
    expect(resized).not.toHaveBeenCalled();
    expect(moved).toHaveBeenCalledTimes(2);
  });

  it('does not resize a milestone', async () => {
    await render();
    expect(bar(2).querySelectorAll('.handle')).toHaveLength(0);
    expect(bar(2).querySelector('.connector')).not.toBeNull();
    key(bar(2), 'ArrowRight', true);
    expect(extended).not.toHaveBeenCalled();
    key(bar(2), 'ArrowRight');
    expect(moved).toHaveBeenCalledWith({ taskId: 3, days: 1 });
  });

  it('draws a link from the dot after a bar onto the bar that should wait', async () => {
    await render();
    pointer(connector(1), 'pointerdown', 452, 92);
    pointer(connector(1), 'pointermove', 530, 122);
    await fixture.whenStable();
    expect(el.querySelector('.link-draft')?.getAttribute('d')).toBe('M 451 92 L 530 122');
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Task 3 will wait for Task 2');
    expect(bar(2).classList).toContain('link-target');

    pointer(connector(1), 'pointerup', 530, 122);
    await fixture.whenStable();
    expect(linked).toHaveBeenCalledWith({ predecessorId: 2, successorId: 3 });
    expect(el.querySelector('.link-draft')).toBeNull();
    expect(moved).not.toHaveBeenCalled();
  });

  it('draws nothing when the link is dropped on empty space or on its own bar', async () => {
    await render();
    await drag(connector(0), 312, 700, 300);
    await drag(connector(0), 312, 250, 60);
    await drag(connector(0), 312, 313, 60);
    expect(linked).not.toHaveBeenCalled();
    expect(opened).not.toHaveBeenCalled();
  });

  it('puts each arrow after the bar it leaves in the tab order', async () => {
    await render();
    const stops = [...el.querySelectorAll('g.bar, .link-focus')].map(
      (stop) => stop.getAttribute('data-task') ?? `arrow ${stop.getAttribute('data-link')}`,
    );
    expect(stops).toEqual(['1', 'arrow 50', '2', 'arrow 51', '3', '4']);
    expect(arrowFocus(50).getAttribute('tabindex')).toBe('0');
    expect(arrowFocus(50).getAttribute('aria-label')).toBe('Task 2 waits for Task 1. Press Delete to remove this link.');
    // What is drawn stays under the bars and is not a tab stop of its own.
    expect(el.querySelector('g.link-group')?.hasAttribute('tabindex')).toBe(false);
  });

  it('removes a clicked arrow with the button and hands the focus to the bar it left', async () => {
    await render();
    el.querySelector('g.link-group .link-hit')!.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
    await fixture.whenStable();
    expect(document.activeElement).toBe(arrowFocus(50));
    expect(arrowFocus(50).classList).toContain('selected');
    const toast = el.querySelector('.gantt-selection')!;
    expect(toast.textContent).toContain('Task 2 waits for Task 1');

    toast.querySelector('button')!.click();
    await fixture.whenStable();
    expect(unlinked).toHaveBeenCalledWith({ successorId: 2, dependencyId: 50 });
    expect(el.querySelector('.gantt-selection')).toBeNull();
    expect(document.activeElement).toBe(bar(0));
  });

  it('removes a focused arrow with the Delete key and keeps the focus on the timeline', async () => {
    await render();
    arrowFocus(51).focus();
    await fixture.whenStable();
    expect(el.querySelector('.gantt-selection')?.textContent).toContain('Task 4 waits for Task 2');
    key(arrowFocus(51), 'Delete');
    await fixture.whenStable();
    expect(unlinked).toHaveBeenCalledWith({ successorId: 4, dependencyId: 51 });
    expect(document.activeElement).toBe(bar(1));
    expect(el.querySelector('.gantt-selection')).toBeNull();
  });

  it('forgets the selected arrow when a bar takes the focus', async () => {
    await render();
    arrowFocus(50).focus();
    await fixture.whenStable();
    expect(el.querySelector('.gantt-selection')).not.toBeNull();
    bar(3).focus();
    await fixture.whenStable();
    expect(el.querySelector('.gantt-selection')).toBeNull();
  });

  it('is not edited at month zoom, where a day is four pixels: a wobble opens the task instead of moving it', async () => {
    await render(true, 'month');
    expect(el.querySelectorAll('.handle, .connector, .link-hit, .link-focus')).toHaveLength(0);
    await drag(body(0), 60, 62);
    expect(opened).toHaveBeenCalledWith(1);
    await drag(body(0), 60, 100);
    key(bar(0), 'ArrowRight');
    key(bar(0), 'ArrowRight', true);
    expect(moved).not.toHaveBeenCalled();
    expect(extended).not.toHaveBeenCalled();
    expect(resized).not.toHaveBeenCalled();
  });

  it('is read-only for people who cannot edit the plan', async () => {
    await render(false);
    expect(el.querySelectorAll('.handle, .connector, .link-hit, .link-focus')).toHaveLength(0);
    el.querySelector('g.link-group .link')!.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }));
    key(bar(0), 'ArrowRight');
    key(bar(0), 'ArrowRight', true);
    await drag(body(1), 400, 400 + 56);
    await fixture.whenStable();
    expect(el.querySelector('.gantt-selection')).toBeNull();
    expect(moved).not.toHaveBeenCalled();
    expect(extended).not.toHaveBeenCalled();
    expect(unlinked).not.toHaveBeenCalled();
    // A click and Enter still open the task.
    expect(opened).toHaveBeenCalledWith(2);
    key(bar(0), 'Enter');
    expect(opened).toHaveBeenLastCalledWith(1);
  });
});
