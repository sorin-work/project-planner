import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { HealthStatus } from '../core/models';
import { HEALTH_LABELS } from './format';

@Component({
  selector: 'app-health-badge',
  template: `<span class="badge" [class]="'badge health-' + status().toLowerCase()">{{ label() }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HealthBadge {
  readonly status = input.required<HealthStatus>();
  protected readonly label = computed(() => HEALTH_LABELS[this.status()]);
}
