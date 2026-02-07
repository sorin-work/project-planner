import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { initials } from './format';

const COLORS = ['#4f6bed', '#2f9e72', '#c2410c', '#7c3aed', '#0e7490', '#d97706', '#be185d', '#4d7c0f'];

@Component({
  selector: 'app-avatar',
  template: `<span class="avatar" [class.small]="small()" [style.background]="color()" [title]="name()">{{ letters() }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Avatar {
  readonly name = input.required<string>();
  readonly small = input(false);

  protected readonly letters = computed(() => initials(this.name()));
  protected readonly color = computed(() => {
    let hash = 0;
    for (const ch of this.name()) {
      hash = (hash * 31 + ch.charCodeAt(0)) | 0;
    }
    return COLORS[Math.abs(hash) % COLORS.length];
  });
}
