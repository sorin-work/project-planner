import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** A thin bar; turns red when {@code value} is over 100 (e.g. budget burn). */
@Component({
  selector: 'app-progress-bar',
  template: `
    <div class="bar" [class.over]="over()" role="progressbar" [attr.aria-valuenow]="rounded()" aria-valuemin="0"
      aria-valuemax="100" [attr.aria-label]="label()">
      <div class="bar-fill" [style.width.%]="width()" [style.background]="color() || null"></div>
      @if (marker() !== null) {
        <div class="bar-marker" [style.left.%]="markerLeft()" title="Planned"></div>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProgressBar {
  readonly value = input<number | null | undefined>(0);
  /** Optional tick, e.g. where progress should be by now. */
  readonly marker = input<number | null>(null);
  readonly label = input('Progress');
  readonly color = input<string | null>(null);

  protected readonly rounded = computed(() => Math.round(this.value() ?? 0));
  protected readonly width = computed(() => Math.max(0, Math.min(100, this.value() ?? 0)));
  protected readonly over = computed(() => (this.value() ?? 0) > 100);
  protected readonly markerLeft = computed(() => Math.max(0, Math.min(100, this.marker() ?? 0)));
}
