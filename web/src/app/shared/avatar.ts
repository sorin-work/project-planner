import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { initials } from './format';

/** Backgrounds for white initials, each at 4.5:1 or more against them (contrast.spec.ts). */
export const AVATAR_COLORS = ['#3d52b6', '#1f7a56', '#c2410c', '#7c3aed', '#0e7490', '#9a5205', '#be185d', '#4d7c0f'];

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
    return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
  });
}
