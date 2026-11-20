import { ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, input, output, signal, viewChild } from '@angular/core';
import { Phase, Task } from '../../core/models';
import { formatRange } from '../../shared/dates';
import { Holidays, NO_HOLIDAYS } from '../../shared/dates';
import { CONNECTOR_RADIUS, DragMode, barAt, barDates, barHandles, dragHint, moveWorkingDays, previewDates, resizeDates } from './gantt-edit';
import { dependencyPhrase } from '../project/dependency-text';
import { GanttBar, GanttLink, MILESTONE_RADIUS, ROW_HEIGHT, Zoom, barSpan, dragDays, layoutGantt } from './gantt-layout';

interface Drag {
  mode: DragMode;
  taskId: number;
  startX: number;
  startY: number;
  dx: number;
  /** False until the pointer has gone far enough to mean it, see DRAG_THRESHOLD. */
  active: boolean;
  pointerId: number;
  /** Where the pointer is on the chart. A link being drawn ends there. */
  x: number;
  y: number;
}

/**
 * How many pixels the pointer has to travel before a press becomes a drag. Below
 * that it is a click that wobbled, and a click opens the task. Without it two
 * pixels were a whole day at the narrower zooms.
 */
export const DRAG_THRESHOLD = 4;

/**
 * SVG timeline: phases and tasks as bars, milestones as diamonds, dependencies as arrows.
 * A bar can be moved, its ends dragged to resize it, and the dot after it dropped on
 * another bar to draw a link. What a drag means in dates is worked out in gantt-edit.ts.
 */
@Component({
  selector: 'app-gantt',
  templateUrl: './gantt.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'cancelDrag()' },
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
  /** The organisation's holidays: shaded like weekends, and skipped when a drag counts working days. */
  readonly holidays = input<Holidays>(NO_HOLIDAYS);
  /** Baseline dates by task, drawn as a thin bar under each bar. Empty when nothing is compared. */
  readonly baseline = input<Map<number, { start: string; due: string }>>(new Map());
  /** What the comparison says about each task, read out with its bar since the thin bar alone is only a picture. */
  readonly baselineLabels = input<Map<number, string>>(new Map());

  /** A bar was moved by this many working days. */
  readonly moved = output<{ taskId: number; days: number }>();
  /** One end of a bar was dragged: the new start or the new due date. */
  readonly resized = output<{ taskId: number; startDate?: string; dueDate?: string }>();
  /**
   * Shift with an arrow key: the due date of a bar should move by this many working
   * days. It is a step and not a date, because the keys repeat faster than the API
   * answers and every press has to count.
   */
  readonly extended = output<{ taskId: number; days: number }>();
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

  /** The bar's label for a screen reader: title, dates, and how it compares with the baseline. */
  protected barLabel(bar: GanttBar): string {
    const compared = this.baselineLabels().get(bar.task.id);
    const label = `${bar.task.title}, ${formatRange(bar.task.startDate, bar.task.dueDate)}`;
    return compared ? `${label}. ${compared}` : label;
  }

  protected readonly layout = computed(() =>
    layoutGantt(this.tasks(), this.phases(), {
      zoom: this.zoom(),
      critical: this.showCritical() ? this.critical() : new Set<number>(),
      collapsed: this.collapsed(),
      broken: this.broken(),
      holidays: this.holidays(),
      baseline: this.baseline(),
    }),
  );

  /** The timeline is edited at day and week zoom. At month zoom a day is four pixels wide, so there it is read-only. */
  protected readonly canEdit = computed(() => this.editable() && this.zoom() !== 'month');

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly svg = viewChild<ElementRef<SVGSVGElement>>('svg');
  private scrolledFor: string | null = null;

  private readonly dragColumns = computed(() => {
    const d = this.drag();
    return d?.active ? dragDays(d.dx, this.layout().dayWidth) : 0;
  });

  /** The bars as drawn: the one being moved or resized is shown where it would land. */
  protected readonly bars = computed<GanttBar[]>(() => {
    const layout = this.layout();
    const d = this.drag();
    if (!d?.active || d.mode === 'link') {
      return layout.bars;
    }
    const mode = d.mode;
    const columns = this.dragColumns();
    return layout.bars.map((bar) => {
      const dates = bar.task.id === d.taskId ? previewDates(bar.task, mode, columns, this.holidays()) : null;
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
    const from = d?.active && d.mode === 'link' ? this.layout().bars.find((b) => b.task.id === d.taskId) : undefined;
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
    if (!d?.active) {
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
    return task ? dragHint(task, d.mode, this.dragColumns(), this.holidays()) : null;
  });

  protected readonly selectedLink = computed(() => this.layout().links.find((l) => l.id === this.selectedLinkId()) ?? null);
  /** The arrows that leave each bar. They follow their bar in the tab order. */
  protected readonly outgoing = computed(() => {
    const byTask = new Map<number, GanttLink[]>();
    for (const link of this.layout().links) {
      byTask.set(link.predecessorId, [...(byTask.get(link.predecessorId) ?? []), link]);
    }
    return byTask;
  });
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
    return `${title(link.successorId)} ${dependencyPhrase(link, title(link.predecessorId))}`;
  }

  /**
   * A press on a bar, or on one of its handles when a mode is given. It becomes a
   * drag once the pointer has moved far enough, and only where the timeline can be
   * edited. A press that stays put is a click and opens the task.
   */
  onPointerDown(event: PointerEvent, bar: GanttBar, mode: DragMode = 'move'): void {
    if (event.button !== 0) {
      return;
    }
    // A handle sits inside its bar: without this the bar would start a move as well.
    event.stopPropagation();
    if (this.canEdit()) {
      (event.target as Element).setPointerCapture?.(event.pointerId);
    }
    this.selectedLinkId.set(null);
    this.drag.set({
      mode,
      taskId: bar.task.id,
      startX: event.clientX,
      startY: event.clientY,
      dx: 0,
      active: false,
      pointerId: event.pointerId,
      ...this.pointOf(event),
    });
  }

  onPointerMove(event: PointerEvent): void {
    const d = this.drag();
    if (d && d.pointerId === event.pointerId) {
      this.drag.set({ ...d, dx: event.clientX - d.startX, active: this.isDrag(d, event), ...this.pointOf(event) });
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
    const taskId = bar.task.id;
    if (!this.isDrag(d, event)) {
      if (d.mode !== 'link') {
        this.opened.emit(taskId);
      }
      return;
    }
    const columns = dragDays(event.clientX - d.startX, layout.dayWidth);
    if (d.mode === 'link') {
      const point = this.pointOf(event);
      const target = barAt(layout.bars, point.x, point.y);
      if (target && target.task.id !== taskId) {
        this.linked.emit({ predecessorId: taskId, successorId: target.task.id });
      }
    } else if (d.mode === 'move') {
      const from = barDates(bar.task)?.from;
      const days = from ? moveWorkingDays(from, columns, this.holidays()) : 0;
      if (days !== 0) {
        this.moved.emit({ taskId, days });
      }
    } else {
      const dates = resizeDates(bar.task, d.mode, columns, this.holidays());
      if (dates) {
        this.resized.emit({ taskId, ...dates });
      }
    }
  }

  /** Escape gives up whatever is being dragged: nothing is sent and the bar is drawn where it was. */
  cancelDrag(): void {
    if (this.drag()) {
      this.drag.set(null);
    }
  }

  onKey(event: KeyboardEvent, bar: GanttBar): void {
    if (event.key === 'Enter') {
      this.opened.emit(bar.task.id);
      return;
    }
    if (!this.canEdit() || (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft')) {
      return;
    }
    event.preventDefault();
    const days = event.key === 'ArrowRight' ? 1 : -1;
    if (!event.shiftKey) {
      // One press is one working day.
      this.moved.emit({ taskId: bar.task.id, days });
    } else if (!bar.milestone) {
      this.extended.emit({ taskId: bar.task.id, days });
    }
  }

  selectLink(link: GanttLink): void {
    if (this.canEdit()) {
      this.selectedLinkId.set(link.id);
    }
  }

  /**
   * A click on an arrow. What is clicked lies under the bars and cannot take the
   * focus in the right place, so the focus goes to the arrow's stand-in after its
   * bar, which selects it.
   */
  pickLink(link: GanttLink): void {
    if (this.canEdit()) {
      this.selectedLinkId.set(link.id);
      this.svg()?.nativeElement.querySelector<SVGElement>(`.link-focus[data-link="${link.id}"]`)?.focus();
    }
  }

  onLinkKey(event: KeyboardEvent, link: GanttLink): void {
    if (!this.canEdit()) {
      return;
    }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      this.removeLink(link);
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.selectedLinkId.set(link.id);
    } else if (event.key === 'Escape') {
      this.keepLink(link);
    }
  }

  removeLink(link: GanttLink): void {
    this.keepLink(link);
    this.unlinked.emit({ successorId: link.successorId, dependencyId: link.id });
  }

  /**
   * Drops the selection and hands the focus to the bar the arrow leaves from. The
   * arrow, or the button that was pressed, is about to go, and the focus would
   * fall back to the page with it.
   */
  keepLink(link: GanttLink): void {
    this.selectedLinkId.set(null);
    this.svg()?.nativeElement.querySelector<SVGElement>(`g.bar[data-task="${link.predecessorId}"]`)?.focus();
  }

  /** Has this press travelled far enough to be a drag? Once it has, it stays one. */
  private isDrag(d: Drag, event: PointerEvent): boolean {
    if (d.active || !this.canEdit()) {
      return d.active;
    }
    const dx = event.clientX - d.startX;
    // A bar only goes sideways. A link goes anywhere.
    const distance = d.mode === 'link' ? Math.hypot(dx, event.clientY - d.startY) : Math.abs(dx);
    return distance >= DRAG_THRESHOLD;
  }

  /** The pointer in the chart's own coordinates, whatever the scroll position. */
  private pointOf(event: PointerEvent): { x: number; y: number } {
    const box = this.svg()?.nativeElement.getBoundingClientRect();
    return { x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0) };
  }
}
