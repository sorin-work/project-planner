import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Announcer } from '../../core/announcer';
import { DependencyType, Phase, Task } from '../../core/models';
import { formatDate, formatRange } from '../../shared/dates';
import { Holidays, NO_HOLIDAYS } from '../../shared/dates';
import {
  CONNECTOR_RADIUS,
  DragMode,
  barAt,
  barDates,
  barHandles,
  dateAt,
  dragHint,
  drawnDates,
  edgeScrollSpeed,
  linkTargets,
  moveWorkingDays,
  previewDates,
  resizeDates,
  stepTarget,
} from './gantt-edit';
import { DEPENDENCY_TYPES, dependencyPhrase } from '../project/dependency-text';
import { BAR_HEIGHT, GanttBar, GanttLink, GanttRow, MILESTONE_RADIUS, ROW_HEIGHT, Zoom, barSpan, dragDays, layoutGantt } from './gantt-layout';

interface Drag {
  /** 'draw' is a drag across the empty row of a task without dates, which gives it dates. */
  mode: DragMode | 'draw';
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
  /** Where the press was on the chart. A drag across an empty row starts there. */
  x0: number;
  /** How far the timeline was scrolled when the press began: scrolling while dragging counts as moving. */
  scroll0: number;
  /** The pointer on the screen, for the frames that scroll while it stands still near an edge. */
  clientX: number;
  clientY: number;
}

/** A link being drawn from the keyboard: the bar it starts from and the one picked so far. */
interface KeyLink {
  fromId: number;
  targetId: number | null;
}

/** A link that was drawn and waits for its type to be picked from the menu. */
interface PendingLink {
  predecessorId: number;
  successorId: number;
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
  host: {
    '(document:keydown.escape)': 'onDocumentEscape()',
    '(document:pointerdown)': 'onDocumentPointerDown($event)',
    '(window:blur)': 'endDrag()',
  },
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
  /**
   * Alt with an arrow key: the start date of a bar should move by this many working days,
   * a step for the same reason as {@link extended}.
   */
  readonly started = output<{ taskId: number; days: number }>();
  /** A link was drawn from the end of one bar onto another, which now waits for it, in the way picked from the menu. */
  readonly linked = output<{ predecessorId: number; successorId: number; type: DependencyType }>();
  /** An arrow was removed. */
  readonly unlinked = output<{ successorId: number; dependencyId: number }>();
  readonly opened = output<number>();

  private readonly announcer = inject(Announcer);

  protected readonly collapsed = signal<Set<number>>(new Set());
  protected readonly keyLink = signal<KeyLink | null>(null);
  protected readonly pendingLink = signal<PendingLink | null>(null);
  protected readonly linkTypes = DEPENDENCY_TYPES;
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
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
    if (!d?.active || d.mode === 'link' || d.mode === 'draw') {
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
    const k = this.keyLink();
    if (k) {
      const bars = this.layout().bars;
      const from = bars.find((b) => b.task.id === k.fromId);
      const target = k.targetId === null ? undefined : bars.find((b) => b.task.id === k.targetId);
      if (!from) {
        return null;
      }
      const anchor = barHandles(from).connector;
      const end = target ? { x: target.x, y: target.y + target.height / 2 } : anchor;
      return { path: `M ${anchor.x} ${anchor.y} L ${end.x} ${end.y}`, from: from.task, target: target?.task ?? null };
    }
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
    if (d.mode === 'draw') {
      const drawn = this.drawDraft();
      return drawn ? `Start ${formatDate(drawn.startDate)}, due ${formatDate(drawn.dueDate)}` : null;
    }
    const task = this.layout().bars.find((b) => b.task.id === d.taskId)?.task;
    return task ? dragHint(task, d.mode, this.dragColumns(), this.holidays()) : null;
  });

  /** What the keys do while a link is drawn from the keyboard, and where it would go. */
  protected readonly keyLinkHint = computed(() => {
    const draft = this.linkDraft();
    if (!this.keyLink() || !draft) {
      return null;
    }
    return draft.target
      ? `${draft.target.title} will wait for ${draft.from.title}. Enter draws the link, Escape gives up.`
      : `Linking from ${draft.from.title}: the arrow keys pick the task that waits for it.`;
  });

  /** The tasks without dates, whose empty rows a drag can give dates to. */
  protected readonly undatedRows = computed<GanttRow[]>(() =>
    this.canEdit() ? this.layout().rows.filter((row) => row.kind === 'task' && row.task && !barDates(row.task)) : [],
  );

  /** The bar a drag across an empty row is drawing, and the dates it would give the task. */
  protected readonly drawDraft = computed(() => {
    const d = this.drag();
    if (!d?.active || d.mode !== 'draw') {
      return null;
    }
    const layout = this.layout();
    const row = layout.rows.find((r) => r.kind === 'task' && r.task?.id === d.taskId);
    if (!row) {
      return null;
    }
    const dates = drawnDates(dateAt(d.x0, layout.start, layout.dayWidth), dateAt(d.x, layout.start, layout.dayWidth), this.holidays());
    const span = barSpan(dates.startDate, dates.dueDate, layout.start, layout.dayWidth);
    return { ...dates, x: span.x, width: span.width, y: row.y + (ROW_HEIGHT - BAR_HEIGHT) / 2, height: BAR_HEIGHT };
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
    inject(DestroyRef).onDestroy(() => this.stopEdge());
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
  onPointerDown(event: PointerEvent, bar: Pick<GanttBar, 'task'>, mode: DragMode | 'draw' = 'move'): void {
    if (event.button !== 0) {
      return;
    }
    this.pendingLink.set(null);
    this.keyLink.set(null);
    // A handle sits inside its bar: without this the bar would start a move as well.
    event.stopPropagation();
    if (this.canEdit()) {
      (event.target as Element).setPointerCapture?.(event.pointerId);
    }
    this.selectedLinkId.set(null);
    const point = this.pointOf(event.clientX, event.clientY);
    this.drag.set({
      mode,
      taskId: bar.task.id,
      startX: event.clientX,
      startY: event.clientY,
      dx: 0,
      active: false,
      pointerId: event.pointerId,
      ...point,
      x0: point.x,
      scroll0: this.scroller()?.nativeElement.scrollLeft ?? 0,
      clientX: event.clientX,
      clientY: event.clientY,
    });
  }

  onPointerMove(event: PointerEvent): void {
    const d = this.drag();
    if (d && d.pointerId === event.pointerId) {
      const active = this.isDrag(d, event);
      this.drag.set({
        ...d,
        dx: this.deltaX(d, event.clientX),
        active,
        ...this.pointOf(event.clientX, event.clientY),
        clientX: event.clientX,
        clientY: event.clientY,
      });
      if (active) {
        this.followEdge(event.clientX);
      }
    }
  }

  onPointerUp(event: PointerEvent): void {
    const d = this.drag();
    if (!d || d.pointerId !== event.pointerId) {
      return;
    }
    const layout = this.layout();
    const drawn = d.mode === 'draw' ? this.drawDraft() : null;
    this.drag.set(null);
    this.stopEdge();
    if (d.mode === 'draw') {
      if (!this.isDrag(d, event)) {
        this.opened.emit(d.taskId);
      } else if (drawn) {
        this.resized.emit({ taskId: d.taskId, startDate: drawn.startDate, dueDate: drawn.dueDate });
      }
      return;
    }
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
    const columns = dragDays(this.deltaX(d, event.clientX), layout.dayWidth);
    if (d.mode === 'link') {
      const point = this.pointOf(event.clientX, event.clientY);
      const target = barAt(layout.bars, point.x, point.y);
      if (target && target.task.id !== taskId) {
        this.askType({ predecessorId: taskId, successorId: target.task.id });
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

  /**
   * Escape gives up whatever is being dragged: nothing is sent and the bar is drawn where it
   * was. It gives up a link drawn from the keyboard, or waiting for its type, the same way.
   */
  cancelDrag(): void {
    this.endDrag();
    const link = this.keyLink() ?? this.pendingLink();
    if (link) {
      this.endLink();
      this.focusBar('fromId' in link ? link.fromId : link.predecessorId);
    }
  }

  /** Escape anywhere on the page: only does something while a drag or a link is live, so that it never takes the focus otherwise. */
  onDocumentEscape(): void {
    if (this.drag() || this.keyLink() || this.pendingLink()) {
      this.cancelDrag();
    }
  }

  /** A press outside the chart and its type menu gives up a link from the keyboard, or one waiting for its type. */
  onDocumentPointerDown(event: Event): void {
    if ((this.keyLink() || this.pendingLink()) && !this.host.nativeElement.contains(event.target as Node)) {
      this.endLink();
    }
  }

  /**
   * The focus left the bar a link from the keyboard starts from: the link is given up, and
   * the focus stays where it went. (The keys that pick a target are taken on that bar, so a
   * link cannot go on from anywhere else.)
   */
  onBarFocusOut(event: FocusEvent): void {
    if (!this.keyLink()) {
      return;
    }
    const to = event.relatedTarget as Node | null;
    if (to && this.host.nativeElement.querySelector('.link-type-menu')?.contains(to)) {
      return;
    }
    this.endLink();
  }

  /** The pointer was let go of in a way that sends no pointerup, or the window lost the focus: the drag ends with nothing sent. */
  onLostCapture(event: PointerEvent): void {
    if (this.drag()?.pointerId === event.pointerId) {
      this.endDrag();
    }
  }

  /** Ends a drag, if there is one, and the scrolling at the edge that may go with it. Nothing is sent. */
  endDrag(): void {
    this.stopEdge();
    if (this.drag()) {
      this.drag.set(null);
    }
  }

  /** Gives up a link being drawn from the keyboard or waiting for its type, and says so once. */
  private endLink(): void {
    if (this.keyLink() || this.pendingLink()) {
      this.keyLink.set(null);
      this.pendingLink.set(null);
      this.announcer.announce('Link cancelled.');
    }
  }

  onKey(event: KeyboardEvent, bar: GanttBar): void {
    if (this.keyLink()) {
      this.onLinkingKey(event);
      return;
    }
    if (event.key === 'Enter') {
      this.opened.emit(bar.task.id);
      return;
    }
    if (event.altKey && (event.key === 'ArrowRight' || event.key === 'ArrowLeft')) {
      // Alt with an arrow is the browser's Back and Forward. On a bar it never is, whether the
      // timeline can be edited here or not.
      event.preventDefault();
    }
    if (!this.canEdit()) {
      return;
    }
    if ((event.key === 'l' || event.key === 'L') && !event.altKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      this.startKeyLink(bar);
      return;
    }
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return;
    }
    // Alt with an arrow is the browser's Back and Forward. On a bar it is always the bar's,
    // whether it moves or not, so that a milestone does not leave the page.
    event.preventDefault();
    const days = event.key === 'ArrowRight' ? 1 : -1;
    if (event.altKey) {
      if (!bar.milestone) {
        this.started.emit({ taskId: bar.task.id, days });
      }
    } else if (event.shiftKey) {
      if (!bar.milestone) {
        this.extended.emit({ taskId: bar.task.id, days });
      }
    } else {
      // One press is one working day.
      this.moved.emit({ taskId: bar.task.id, days });
    }
  }

  /** L on a bar: a link starts from it, and the arrow keys pick the task that will wait for it. */
  private startKeyLink(bar: GanttBar): void {
    this.pendingLink.set(null);
    this.keyLink.set({ fromId: bar.task.id, targetId: null });
    this.announcer.announce(
      `Drawing a link from ${bar.task.title}. The arrow keys pick the task that waits for it, Enter draws it, Escape gives up.`,
    );
  }

  /** The keys while a link is drawn from the keyboard. The focus stays on the bar it starts from. */
  private onLinkingKey(event: KeyboardEvent): void {
    const k = this.keyLink()!;
    const from = this.layout().bars.find((b) => b.task.id === k.fromId);
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancelDrag();
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      if (k.targetId !== null) {
        this.keyLink.set(null);
        this.askType({ predecessorId: k.fromId, successorId: k.targetId });
      } else {
        this.announcer.announce('Pick the task that waits with the arrow keys first.');
      }
      return;
    }
    const step = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1 : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 0;
    if (step === 0 || !from) {
      return;
    }
    event.preventDefault();
    const target = stepTarget(linkTargets(this.layout().bars, k.fromId), k.targetId, step);
    if (target) {
      this.keyLink.set({ fromId: k.fromId, targetId: target.task.id });
      this.announcer.announce(`${target.task.title} will wait for ${from.task.title}.`);
    } else {
      this.announcer.announce('There is no other task to link to.');
    }
  }

  /** A link was dropped or picked: the menu asks how the one task waits for the other. */
  private askType(link: PendingLink): void {
    this.pendingLink.set(link);
    const title = (id: number) => this.titles().get(id) ?? `#${id}`;
    this.announcer.announce(`How does ${title(link.successorId)} wait for ${title(link.predecessorId)}? Pick one of four.`);
    // The menu is drawn on the next change detection; its first choice takes the focus then.
    setTimeout(() => this.host.nativeElement.querySelector<HTMLButtonElement>('.link-type-menu button')?.focus());
  }

  /** The type was picked: the link is drawn, and the focus goes back to the bar it leaves from. */
  pickType(type: DependencyType): void {
    const link = this.pendingLink();
    if (!link) {
      return;
    }
    this.pendingLink.set(null);
    this.linked.emit({ ...link, type });
    this.focusBar(link.predecessorId);
  }

  /** The words for the successor waiting in one of the four ways. */
  protected typeLabel(link: PendingLink, label: string): string {
    return `${this.titles().get(link.successorId) ?? '#' + link.successorId} ${label}`;
  }

  private focusBar(taskId: number): void {
    this.svg()?.nativeElement.querySelector<SVGElement>(`g.bar[data-task="${taskId}"]`)?.focus();
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
    this.focusBar(link.predecessorId);
  }

  /** Has this press travelled far enough to be a drag? Once it has, it stays one. */
  private isDrag(d: Drag, event: PointerEvent): boolean {
    if (d.active || !this.canEdit()) {
      return d.active;
    }
    const dx = this.deltaX(d, event.clientX);
    // A bar only goes sideways. A link goes anywhere.
    const distance = d.mode === 'link' ? Math.hypot(dx, event.clientY - d.startY) : Math.abs(dx);
    return distance >= DRAG_THRESHOLD;
  }

  /** How far the pointer went across the chart: on the screen, plus what the timeline scrolled under it. */
  private deltaX(d: Drag, clientX: number): number {
    return clientX - d.startX + ((this.scroller()?.nativeElement.scrollLeft ?? 0) - d.scroll0);
  }

  /** The pointer in the chart's own coordinates, whatever the scroll position. */
  private pointOf(clientX: number, clientY: number): { x: number; y: number } {
    const box = this.svg()?.nativeElement.getBoundingClientRect();
    return { x: clientX - (box?.left ?? 0), y: clientY - (box?.top ?? 0) };
  }

  private edgeFrame: number | null = null;
  private edgeSpeed = 0;

  /**
   * Near the left or right edge of the visible part, a drag scrolls the timeline, a frame at
   * a time and faster closer to the edge (see edgeScrollSpeed), so that a bar can be moved
   * or linked to a day that is not on screen.
   */
  private followEdge(clientX: number): void {
    const el = this.scroller()?.nativeElement;
    if (!el) {
      return;
    }
    const box = el.getBoundingClientRect();
    this.edgeSpeed = edgeScrollSpeed(clientX, box.left, box.right);
    if (this.edgeSpeed !== 0 && this.edgeFrame === null && typeof requestAnimationFrame === 'function') {
      this.edgeFrame = requestAnimationFrame(() => this.edgeStep());
    }
  }

  private edgeStep(): void {
    this.edgeFrame = null;
    const el = this.scroller()?.nativeElement;
    const d = this.drag();
    if (!el || !d?.active || this.edgeSpeed === 0) {
      return;
    }
    const before = el.scrollLeft;
    el.scrollLeft = before + this.edgeSpeed;
    if (el.scrollLeft === before) {
      // At the end of the timeline: nothing more to scroll.
      return;
    }
    this.drag.set({ ...d, dx: this.deltaX(d, d.clientX), ...this.pointOf(d.clientX, d.clientY) });
    this.edgeFrame = requestAnimationFrame(() => this.edgeStep());
  }

  private stopEdge(): void {
    this.edgeSpeed = 0;
    if (this.edgeFrame !== null) {
      cancelAnimationFrame(this.edgeFrame);
      this.edgeFrame = null;
    }
  }
}
