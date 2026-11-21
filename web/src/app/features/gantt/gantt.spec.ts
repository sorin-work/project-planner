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
  task(2, '2026-03-09', '2026-03-10', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }] }),
  task(3, '2026-03-13', '2026-03-13', { milestone: true }),
  task(4, '2026-03-19', '2026-03-20', { predecessors: [{ id: 51, predecessorId: 2, successorId: 4, lagDays: 0, type: 'FS' }] }),
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
    expect(el.querySelector('.link-draft')).toBeNull();
    // A small menu asks how the one waits for the other before anything is drawn.
    expect(linked).not.toHaveBeenCalled();
    const choices = [...el.querySelectorAll<HTMLButtonElement>('.link-type-menu button')];
    expect(choices.map((b) => b.textContent!.trim())).toEqual([
      'Task 3 starts after it ends', 'Task 3 starts when it starts', 'Task 3 ends when it ends', 'Task 3 ends before it starts', 'Cancel',
    ]);
    choices[1].click();
    await fixture.whenStable();
    expect(linked).toHaveBeenCalledWith({ predecessorId: 2, successorId: 3, type: 'SS' });
    expect(el.querySelector('.link-type-menu')).toBeNull();
    expect(moved).not.toHaveBeenCalled();
  });

  it('draws nothing when the menu after the drop is cancelled or Escape is pressed', async () => {
    await render();
    await drag(connector(1), 452, 530, 122);
    el.querySelector<HTMLButtonElement>('.link-type-menu button:last-child')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.link-type-menu')).toBeNull();
    await drag(connector(1), 452, 530, 122);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await fixture.whenStable();
    expect(el.querySelector('.link-type-menu')).toBeNull();
    expect(linked).not.toHaveBeenCalled();
  });

  it('draws a link from the keyboard: L, the arrow keys to a target, Enter, and the type', async () => {
    await render();
    key(bar(0), 'l');
    await fixture.whenStable();
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Linking from Task 1');
    // Down goes through the other bars from the top, round from the last to the first.
    key(bar(0), 'ArrowDown');
    key(bar(0), 'ArrowDown');
    await fixture.whenStable();
    expect(bar(2).classList).toContain('link-target');
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Task 3 will wait for Task 1');
    key(bar(0), 'ArrowUp');
    await fixture.whenStable();
    expect(bar(1).classList).toContain('link-target');
    // The arrow keys picked a target: nothing moved.
    expect(moved).not.toHaveBeenCalled();

    key(bar(0), 'Enter');
    await fixture.whenStable();
    expect(opened).not.toHaveBeenCalled();
    el.querySelector<HTMLButtonElement>('.link-type-menu button')!.click();
    await fixture.whenStable();
    expect(linked).toHaveBeenCalledWith({ predecessorId: 1, successorId: 2, type: 'FS' });
  });

  it('gives up a link from the keyboard on Escape', async () => {
    await render();
    key(bar(0), 'L');
    key(bar(0), 'ArrowDown');
    key(bar(0), 'Escape');
    await fixture.whenStable();
    expect(el.querySelector('.link-draft')).toBeNull();
    expect(el.querySelector('.link-type-menu')).toBeNull();
    // Back to normal: an arrow moves the bar again.
    key(bar(0), 'ArrowRight');
    expect(moved).toHaveBeenCalledWith({ taskId: 1, days: 1 });
    expect(linked).not.toHaveBeenCalled();
  });

  it('gives up a link from the keyboard when the focus leaves its bar, and the keys work on the next bar as always', async () => {
    await render();
    key(bar(0), 'l');
    key(bar(0), 'ArrowDown');
    await fixture.whenStable();
    expect(el.querySelector('.link-draft')).not.toBeNull();

    // Tab to the next bar.
    bar(0).dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: bar(1) }));
    await fixture.whenStable();
    expect(el.querySelector('.link-draft')).toBeNull();
    key(bar(1), 'ArrowRight');
    expect(moved).toHaveBeenCalledWith({ taskId: 2, days: 1 });
    key(bar(1), 'Enter');
    expect(opened).toHaveBeenCalledWith(2);
    expect(el.querySelector('.link-type-menu')).toBeNull();
    expect(linked).not.toHaveBeenCalled();
  });

  it('gives up a link from the keyboard on a press outside the chart, and Escape elsewhere does nothing after that', async () => {
    await render();
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    key(bar(0), 'l');
    outside.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    await fixture.whenStable();
    expect(el.querySelector('.gantt-hint')).toBeNull();
    outside.focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    // Nothing was live: the focus stays where it is.
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it('ends a drag when the pointer capture is lost, without sending anything', async () => {
    await render();
    pointer(body(0), 'pointerdown', 250);
    pointer(body(0), 'pointermove', 250 + 3 * 28);
    await fixture.whenStable();
    body(0).dispatchEvent(new PointerEvent('lostpointercapture', { bubbles: true, pointerId: 1 }));
    await fixture.whenStable();
    expect(el.querySelector('.gantt-hint')).toBeNull();
    pointer(body(0), 'pointerup', 250 + 3 * 28);
    expect(moved).not.toHaveBeenCalled();
    expect(opened).not.toHaveBeenCalled();
  });

  it('never lets Alt and an arrow go Back, also where the timeline cannot be edited', async () => {
    await render(false);
    const event = new KeyboardEvent('keydown', { key: 'ArrowLeft', altKey: true, bubbles: true, cancelable: true });
    bar(0).dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(moved).not.toHaveBeenCalled();
  });

  it('asks for a start date step with Alt and an arrow, and never lets the browser go Back', async () => {
    await render();
    const started = vi.fn();
    fixture.componentInstance.started.subscribe(started);
    const press = (target: EventTarget) => {
      const event = new KeyboardEvent('keydown', { key: 'ArrowLeft', altKey: true, bubbles: true, cancelable: true });
      target.dispatchEvent(event);
      return event;
    };
    expect(press(bar(0)).defaultPrevented).toBe(true);
    expect(started).toHaveBeenCalledWith({ taskId: 1, days: -1 });
    // A milestone has one date: nothing to move, and still no Back.
    expect(press(bar(2)).defaultPrevented).toBe(true);
    expect(started).toHaveBeenCalledTimes(1);
    expect(moved).not.toHaveBeenCalled();
    expect(extended).not.toHaveBeenCalled();
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
    expect(arrowFocus(50).getAttribute('aria-label')).toBe('Task 2 starts after Task 1 ends. Press Delete to remove this link.');
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
    expect(toast.textContent).toContain('Task 2 starts after Task 1 ends');

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
    expect(el.querySelector('.gantt-selection')?.textContent).toContain('Task 4 starts after Task 2 ends');
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

describe('Gantt, a task without dates', () => {
  it('gets a start and a due date from a drag across its empty row, snapped onto working days', async () => {
    const resized = vi.fn();
    const fixture = TestBed.createComponent(Gantt);
    // Task 9 has no dates, so it has no bar: its row is the second, y 76 to 108.
    fixture.componentRef.setInput('tasks', [task(1, '2026-03-02', '2026-03-05'), { ...task(9, '', ''), startDate: undefined, dueDate: undefined }]);
    fixture.componentRef.setInput('editable', true);
    fixture.componentInstance.resized.subscribe(resized);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const row = el.querySelector('.empty-row[data-empty-task="9"]')!;
    expect(row).not.toBeNull();
    const at = (type: string, clientX: number) =>
      row.dispatchEvent(new PointerEvent(type, { bubbles: true, button: 0, pointerId: 1, clientX, clientY: 90 }));

    // From Saturday 28 February (x 140) to Wednesday 4 March (x 252): Monday to Wednesday.
    at('pointerdown', 150);
    at('pointermove', 260);
    await fixture.whenStable();
    expect(el.querySelector('.draw-draft')).not.toBeNull();
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Start');
    at('pointerup', 260);
    await fixture.whenStable();
    expect(resized).toHaveBeenCalledWith({ taskId: 9, startDate: '2026-03-02', dueDate: '2026-03-04' });
    expect(el.querySelector('.draw-draft')).toBeNull();
  });
});
