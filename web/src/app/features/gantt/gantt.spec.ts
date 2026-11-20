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
// second, the launch milestone sits on Friday 13 in the third. Rows are 32 pixels high below a 44 pixel header.
const tasks = [
  task(1, '2026-03-02', '2026-03-05'),
  task(2, '2026-03-09', '2026-03-10', { predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0 }] }),
  task(3, '2026-03-13', '2026-03-13', { milestone: true }),
];

describe('Gantt editing', () => {
  let fixture: ComponentFixture<Gantt>;
  let el: HTMLElement;
  const moved = vi.fn();
  const resized = vi.fn();
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

  function key(target: Element, name: string, shiftKey = false): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: name, shiftKey, bubbles: true, cancelable: true }));
  }

  it('moves a bar by working days: two columns from Thursday is Monday', async () => {
    await render();
    await drag(body(0), 250, 250 + 2 * 28);
    expect(moved).toHaveBeenCalledWith({ taskId: 1, days: 2 });

    // Task 2 starts on a Monday. One column to the left is Sunday, so it goes to Friday.
    await drag(body(1), 400, 400 - 28);
    expect(moved).toHaveBeenLastCalledWith({ taskId: 2, days: -1 });
    expect(resized).not.toHaveBeenCalled();
  });

  it('shows where the bar would land while it is dragged', async () => {
    await render();
    pointer(body(0), 'pointerdown', 250);
    pointer(body(0), 'pointermove', 250 + 5 * 28);
    await fixture.whenStable();
    // Five columns from Monday 2 March is Saturday: the bar is drawn on the Monday after.
    expect(body(0).getAttribute('x')).toBe(String(196 + 7 * 28 + 1));
    expect(el.querySelector('.gantt-hint')?.textContent).toContain('Move by +5 working days');
    pointer(body(0), 'pointerup', 250 + 5 * 28);
    await fixture.whenStable();
    expect(body(0).getAttribute('x')).toBe('197');
    expect(el.querySelector('.gantt-hint')).toBeNull();
  });

  it('opens the task on a click that did not move', async () => {
    await render();
    await drag(body(0), 250, 251);
    expect(opened).toHaveBeenCalledWith(1);
    await drag(handle(0, 'end'), 306, 306);
    expect(opened).toHaveBeenCalledTimes(2);
    expect(moved).not.toHaveBeenCalled();
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

  it('moves with the arrow keys and changes the due date with Shift', async () => {
    await render();
    key(bar(0), 'ArrowRight');
    expect(moved).toHaveBeenLastCalledWith({ taskId: 1, days: 1 });
    key(bar(0), 'ArrowLeft');
    expect(moved).toHaveBeenLastCalledWith({ taskId: 1, days: -1 });

    key(bar(0), 'ArrowRight', true);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, dueDate: '2026-03-06' });
    key(bar(0), 'ArrowLeft', true);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 1, dueDate: '2026-03-04' });
    // Task 2 runs Monday to Tuesday: its due date can come back to Monday, the start, and no further.
    key(bar(1), 'ArrowLeft', true);
    expect(resized).toHaveBeenLastCalledWith({ taskId: 2, dueDate: '2026-03-09' });
    expect(resized).toHaveBeenCalledTimes(3);
    expect(moved).toHaveBeenCalledTimes(2);
  });

  it('does not resize a milestone', async () => {
    await render();
    expect(bar(2).querySelectorAll('.handle')).toHaveLength(0);
    expect(bar(2).querySelector('.connector')).not.toBeNull();
    key(bar(2), 'ArrowRight', true);
    expect(resized).not.toHaveBeenCalled();
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
    expect(linked).not.toHaveBeenCalled();
    expect(opened).not.toHaveBeenCalled();
  });

  it('removes a selected arrow with the button', async () => {
    await render();
    const arrow = el.querySelector<SVGGElement>('g.link-group')!;
    expect(arrow.getAttribute('aria-label')).toBe('Task 2 waits for Task 1. Press Delete to remove this link.');
    arrow.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await fixture.whenStable();
    expect(arrow.classList).toContain('selected');
    const toast = el.querySelector('.gantt-selection')!;
    expect(toast.textContent).toContain('Task 2 waits for Task 1');

    toast.querySelector('button')!.click();
    await fixture.whenStable();
    expect(unlinked).toHaveBeenCalledWith({ successorId: 2, dependencyId: 50 });
    expect(el.querySelector('.gantt-selection')).toBeNull();
  });

  it('removes a focused arrow with the Delete key', async () => {
    await render();
    const arrow = el.querySelector<SVGGElement>('g.link-group')!;
    expect(arrow.getAttribute('tabindex')).toBe('0');
    key(arrow, 'Delete');
    expect(unlinked).toHaveBeenCalledWith({ successorId: 2, dependencyId: 50 });
  });

  it('has no handles at month zoom, where a day is four pixels, but keeps the keys', async () => {
    await render(true, 'month');
    expect(el.querySelectorAll('.handle, .connector')).toHaveLength(0);
    key(bar(0), 'ArrowRight', true);
    expect(resized).toHaveBeenCalledWith({ taskId: 1, dueDate: '2026-03-06' });
  });

  it('is read-only for people who cannot edit the plan', async () => {
    await render(false);
    expect(el.querySelectorAll('.handle, .connector, .link-hit')).toHaveLength(0);
    const arrow = el.querySelector<SVGGElement>('g.link-group')!;
    expect(arrow.getAttribute('tabindex')).toBeNull();
    arrow.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    key(arrow, 'Delete');
    key(bar(0), 'ArrowRight');
    key(bar(0), 'ArrowRight', true);
    await drag(body(0), 250, 250 + 56);
    await fixture.whenStable();
    expect(el.querySelector('.gantt-selection')).toBeNull();
    expect(moved).not.toHaveBeenCalled();
    expect(resized).not.toHaveBeenCalled();
    expect(unlinked).not.toHaveBeenCalled();
    // Enter still opens the task.
    key(bar(0), 'Enter');
    expect(opened).toHaveBeenCalledWith(1);
  });
});
