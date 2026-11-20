import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, input, output, signal, viewChild } from '@angular/core';
import { Phase, Task } from '../../core/models';
import { formatRange } from '../../shared/dates';
import { CONNECTOR_RADIUS, DragMode, barAt, barDates, barHandles, dragHint, moveWorkingDays, previewDates, resizeDates } from './gantt-edit';
import { GanttBar, GanttLink, MILESTONE_RADIUS, ROW_HEIGHT, Zoom, barSpan, dragDays, layoutGantt } from './gantt-layout';

interface Drag {
  mode: DragMode;
  taskId: number;
  startX: number;
  dx: number;
  pointerId: number;
  /** Where the pointer is on the chart. A link being drawn ends there. */
  x: number;
  y: number;
}

/** A click that moved less than this is a click, not a drag. */
const CLICK_SLOP = 3;

/**
 * SVG timeline: phases and tasks as bars, milestones as diamonds, dependencies as arrows.
 * A bar can be moved, its ends dragged to resize it, and the dot after it dropped on
 * another bar to draw a link. What a drag means in dates is worked out in gantt-edit.ts.
 */
@Component({
  selector: 'app-gantt',
  templateUrl: './gantt.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Gantt {
  readonly tasks = input.required<Task[]>();
  readonly phases = input<Phase[]>([]);
  readonly critical = input<Set<number>>(new Set());
  /** Link keys (see linkKey) of the dependencies to draw as broken. */
  readonly broken = input<Set<string>>(new Set());
  readonly showCritical = input(true);
  readonly zoom = input<Zoom>('day');
  readonly editable = input(false);

  /** A bar was moved by this many working days. */
  readonly moved = output<{ taskId: number; days: number }>();
  /** One end of a bar was dragged: the new start or the new due date. */
  readonly resized = output<{ taskId: number; startDate?: string; dueDate?: string }>();
  /** A link was drawn from the end of one bar onto another, which now waits for it. */
  readonly linked = output<{ predecessorId: number; successorId: number }>();
  /** An arrow was removed. */
  readonly unlinked = output<{ successorId: number; dependencyId: number }>();
  readonly opened = output<number>();

  protected readonly collapsed = signal<Set<number>>(new Set());
  protected readonly drag = signal<Drag | null>(null);
  protected readonly selectedLinkId = signal<number | null>(null);
  protected readonly rowHeight = ROW_HEIGHT;
  protected readonly connectorRadius = CONNECTOR_RADIUS;
  protected readonly formatRange = formatRange;
  protected readonly grabAreas = barHandles;

  protected readonly layout = computed(() =>
    layoutGantt(this.tasks(), this.phases(), {
      zoom: this.zoom(),
      critical: this.showCritical() ? this.critical() : new Set<number>(),
      collapsed: this.collapsed(),
      broken: this.broken(),
    }),
  );

  /** At month zoom a day is four pixels wide: no room for handles. Bars still move, and the keys still work. */
  protected readonly handles = computed(() => this.editable() && this.zoom() !== 'month');

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly svg = viewChild<ElementRef<SVGSVGElement>>('svg');
  private scrolledFor: string | null = null;

  private readonly dragColumns = computed(() => {
    const d = this.drag();
    return d ? dragDays(d.dx, this.layout().dayWidth) : 0;
  });

  /** The bars as drawn: the one being moved or resized is shown where it would land. */
  protected readonly bars = computed<GanttBar[]>(() => {
    const layout = this.layout();
    const d = this.drag();
    if (!d || d.mode === 'link') {
      return layout.bars;
    }
    const mode = d.mode;
    const columns = this.dragColumns();
    return layout.bars.map((bar) => {
      const dates = bar.task.id === d.taskId ? previewDates(bar.task, mode, columns) : null;
      if (!dates) {
        return bar;
      }
      const span = barSpan(dates.from, dates.to, layout.start, layout.dayWidth);
      return { ...bar, x: span.x, width: bar.milestone ? bar.width : span.width };
    });
  });

  /** The line that follows the pointer while a link is drawn, and the bar it would be dropped on. */
  protected readonly linkDraft = computed(() => {
    const d = this.drag();
    const from = d?.mode === 'link' ? this.layout().bars.find((b) => b.task.id === d.taskId) : undefined;
    if (!d || !from) {
      return null;
    }
    const anchor = barHandles(from).connector;
    const target = barAt(this.layout().bars, d.x, d.y);
    return {
      path: `M ${anchor.x} ${anchor.y} L ${d.x} ${d.y}`,
      from: from.task,
      target: target && target.task.id !== from.task.id ? target.task : null,
    };
  });

  protected readonly hint = computed(() => {
    const d = this.drag();
    if (!d) {
      return null;
    }
    if (d.mode === 'link') {
      const draft = this.linkDraft();
      if (!draft) {
        return null;
      }
      return draft.target
        ? `${draft.target.title} will wait for ${draft.from.title}`
        : `Drop on the task that waits for ${draft.from.title}`;
    }
    const task = this.layout().bars.find((b) => b.task.id === d.taskId)?.task;
    return task ? dragHint(task, d.mode, this.dragColumns()) : null;
  });

  protected readonly selectedLink = computed(() => this.layout().links.find((l) => l.id === this.selectedLinkId()) ?? null);
  private readonly titles = computed(() => new Map(this.tasks().map((t) => [t.id, t.title])));

  constructor() {
    // Open the timeline around today rather than at the project start, once per zoom level.
    afterRenderEffect(() => {
      const el = this.scroller()?.nativeElement;
      const layout = this.layout();
      const key = `${this.zoom()}:${layout.start}`;
      if (!el || layout.todayX === null || this.scrolledFor === key) {
        return;
      }
      this.scrolledFor = key;
      el.scrollLeft = Math.max(0, layout.todayX - el.clientWidth / 3);
    });
  }

  toggle(phaseId: number | null | undefined): void {
    const key = phaseId ?? 0;
    this.collapsed.update((set) => {
      const next = new Set(set);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  isCollapsed(phaseId: number | null | undefined): boolean {
    return this.collapsed().has(phaseId ?? 0);
  }

  diamond(bar: GanttBar): string {
    const cx = bar.x + bar.width / 2;
    const cy = bar.y + bar.height / 2;
    const r = MILESTONE_RADIUS;
    return `${cx},${cy - r} ${cx + r},${cy} ${cx},${cy + r} ${cx - r},${cy}`;
  }

  linkLabel(link: GanttLink): string {
    const title = (id: number) => this.titles().get(id) ?? `#${id}`;
    return `${title(link.successorId)} waits for ${title(link.predecessorId)}`;
  }

  /** Starts a drag on a bar, or on one of its handles when a mode is given. */
  onPointerDown(event: PointerEvent, bar: GanttBar, mode: DragMode = 'move'): void {
    if (!this.editable() || event.button !== 0) {
      return;
    }
    // A handle sits inside its bar: without this the bar would start a move as well.
    event.stopPropagation();
    (event.target as Element).setPointerCapture?.(event.pointerId);
    this.selectedLinkId.set(null);
    this.drag.set({ mode, taskId: bar.task.id, startX: event.clientX, dx: 0, pointerId: event.pointerId, ...this.pointOf(event) });
  }

  onPointerMove(event: PointerEvent): void {
    const d = this.drag();
    if (d && d.pointerId === event.pointerId) {
      this.drag.set({ ...d, dx: event.clientX - d.startX, ...this.pointOf(event) });
    }
  }

  onPointerUp(event: PointerEvent): void {
    const d = this.drag();
    if (!d || d.pointerId !== event.pointerId) {
      return;
    }
    const layout = this.layout();
    this.drag.set(null);
    const bar = layout.bars.find((b) => b.task.id === d.taskId);
    if (!bar) {
      return;
    }
    const dx = event.clientX - d.startX;
    const columns = dragDays(dx, layout.dayWidth);
    const taskId = bar.task.id;
    if (d.mode === 'link') {
      const point = this.pointOf(event);
      const target = barAt(layout.bars, point.x, point.y);
      if (target && target.task.id !== taskId) {
        this.linked.emit({ predecessorId: taskId, successorId: target.task.id });
      }
      return;
    }
    if (d.mode === 'move') {
      const from = barDates(bar.task)?.from;
      const days = from ? moveWorkingDays(from, columns) : 0;
      if (days !== 0) {
        this.moved.emit({ taskId, days });
        return;
      }
    } else {
      const dates = resizeDates(bar.task, d.mode, columns);
      if (dates) {
        this.resized.emit({ taskId, ...dates });
        return;
      }
    }
    if (Math.abs(dx) < CLICK_SLOP) {
      this.opened.emit(taskId);
    }
  }

  onKey(event: KeyboardEvent, bar: GanttBar): void {
    if (event.key === 'Enter') {
      this.opened.emit(bar.task.id);
      return;
    }
    if (!this.editable() || (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft')) {
      return;
    }
    event.preventDefault();
    const step = event.key === 'ArrowRight' ? 1 : -1;
    if (!event.shiftKey) {
      // One press is one working day.
      this.moved.emit({ taskId: bar.task.id, days: step });
    } else if (!bar.milestone) {
      // Shift moves the due date: one column, and on past a weekend.
      const dates = resizeDates(bar.task, 'end', step);
      if (dates) {
        this.resized.emit({ taskId: bar.task.id, ...dates });
      }
    }
  }

  selectLink(link: GanttLink): void {
    if (this.editable()) {
      this.selectedLinkId.set(link.id);
    }
  }

  onLinkKey(event: KeyboardEvent, link: GanttLink): void {
    if (!this.editable()) {
      return;
    }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      this.removeLink(link);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.selectedLinkId.set(link.id);
    } else if (event.key === 'Escape') {
      this.selectedLinkId.set(null);
    }
  }

  removeLink(link: GanttLink): void {
    this.selectedLinkId.set(null);
    this.unlinked.emit({ successorId: link.successorId, dependencyId: link.id });
  }

  /** The pointer in the chart's own coordinates, whatever the scroll position. */
  private pointOf(event: PointerEvent): { x: number; y: number } {
    const box = this.svg()?.nativeElement.getBoundingClientRect();
    return { x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0) };
  }
}
