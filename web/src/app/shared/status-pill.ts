import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TaskStatus } from '../core/models';
import { TASK_STATUS_LABELS } from './format';

@Component({
  selector: 'app-status-pill',
  template: `<span [class]="'pill status-' + status().toLowerCase()">{{ label() }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPill {
  readonly status = input.required<TaskStatus>();
  protected readonly label = computed(() => TASK_STATUS_LABELS[this.status()]);
}
