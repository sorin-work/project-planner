import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { Phase, Task } from '../../core/models';
import { formatRange } from '../../shared/dates';
import { GanttBar, ROW_HEIGHT, Zoom, dragDays, layoutGantt } from './gantt-layout';

interface Drag {
  taskId: number;
  startX: number;
  dx: number;
  pointerId: number;
}

/** SVG timeline: phases and tasks as bars, milestones as diamonds, dependencies as arrows. */
@Component({
  selector: 'app-gantt',
  templateUrl: './gantt.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Gantt {
  readonly tasks = input.required<Task[]>();
  readonly phases = input<Phase[]>([]);
  readonly critical = input<Set<number>>(new Set());
  readonly showCritical = input(true);
  readonly zoom = input<Zoom>('day');
  readonly editable = input(false);

  /** A bar was dragged by this many days. */
  readonly moved = output<{ taskId: number; days: number }>();
  readonly opened = output<number>();

  protected readonly collapsed = signal<Set<number>>(new Set());
  protected readonly drag = signal<Drag | null>(null);
  protected readonly rowHeight = ROW_HEIGHT;
  protected readonly formatRange = formatRange;

  protected readonly layout = computed(() =>
    layoutGantt(this.tasks(), this.phases(), {
      zoom: this.zoom(),
      critical: this.showCritical() ? this.critical() : new Set<number>(),
      collapsed: this.collapsed(),
    }),
  );


  protected readonly dragPreviewDays = computed(() => {
    const d = this.drag();
    return d ? dragDays(d.dx, this.layout().dayWidth) : 0;
  });

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

  offset(bar: GanttBar): number {
    const d = this.drag();
    return d && d.taskId === bar.task.id ? this.dragPreviewDays() * this.layout().dayWidth : 0;
  }

  diamond(bar: GanttBar): string {
    const cx = bar.x + bar.width / 2;
    const cy = bar.y + bar.height / 2;
    const r = 8;
    return `${cx},${cy - r} ${cx + r},${cy} ${cx},${cy + r} ${cx - r},${cy}`;
  }

  onPointerDown(event: PointerEvent, bar: GanttBar): void {
    if (!this.editable() || event.button !== 0) {
      return;
    }
    (event.target as Element).setPointerCapture?.(event.pointerId);
    this.drag.set({ taskId: bar.task.id, startX: event.clientX, dx: 0, pointerId: event.pointerId });
  }

  onPointerMove(event: PointerEvent): void {
    const d = this.drag();
    if (d && d.pointerId === event.pointerId) {
      this.drag.set({ ...d, dx: event.clientX - d.startX });
    }
  }

  onPointerUp(event: PointerEvent, bar: GanttBar): void {
    const d = this.drag();
    if (!d || d.pointerId !== event.pointerId) {
      return;
    }
    const days = dragDays(d.dx, this.layout().dayWidth);
    this.drag.set(null);
    if (days !== 0) {
      this.moved.emit({ taskId: bar.task.id, days });
    } else if (Math.abs(d.dx) < 3) {
      this.opened.emit(bar.task.id);
    }
  }

  onKey(event: KeyboardEvent, bar: GanttBar): void {
    if (event.key === 'Enter') {
      this.opened.emit(bar.task.id);
    } else if (this.editable() && (event.key === 'ArrowRight' || event.key === 'ArrowLeft')) {
      event.preventDefault();
      this.moved.emit({ taskId: bar.task.id, days: event.key === 'ArrowRight' ? 1 : -1 });
    }
  }
}
