import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Member } from '../../core/models';
import { ActiveMention, activeMention, insertMention } from '../../shared/mentions';

const MAX_SUGGESTIONS = 6;

/** A textarea that suggests teammates after "@" and inserts their handle. */
@Component({
  selector: 'app-comment-box',
  imports: [FormsModule],
  template: `
    <div class="comment-box">
      <textarea #area rows="3" [(ngModel)]="text" name="comment" placeholder="Write a comment… use @ to mention someone"
        aria-label="Comment" (input)="update()" (click)="update()" (keydown)="onKeydown($event)"></textarea>
      @if (suggestions().length) {
        <ul class="suggestions" role="listbox" aria-label="People">
          @for (m of suggestions(); track m.userId; let i = $index) {
            <li role="option" [attr.aria-selected]="i === highlighted()" [class.active]="i === highlighted()"
              (mousedown)="$event.preventDefault(); choose(m)">
              <strong>{{ m.name }}</strong> <span class="muted">&#64;{{ m.handle }}</span>
            </li>
          }
        </ul>
      }
      <div class="actions">
        <button type="button" class="primary small" [disabled]="!text.trim() || busy()" (click)="send()">Comment</button>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommentBox {
  readonly members = input<Member[]>([]);
  readonly busy = input(false);
  readonly submitted = output<string>();

  private readonly area = viewChild.required<ElementRef<HTMLTextAreaElement>>('area');
  protected text = '';
  private readonly mention = signal<ActiveMention | null>(null);
  protected readonly highlighted = signal(0);

  protected readonly suggestions = computed(() => {
    const mention = this.mention();
    if (!mention) {
      return [];
    }
    return this.members()
      .filter((m) => m.handle.startsWith(mention.query) || m.name.toLowerCase().includes(mention.query))
      .slice(0, MAX_SUGGESTIONS);
  });

  update(): void {
    const el = this.area().nativeElement;
    this.mention.set(activeMention(this.text, el.selectionStart ?? this.text.length));
    this.highlighted.set(0);
  }

  onKeydown(event: KeyboardEvent): void {
    const list = this.suggestions();
    if (!list.length) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.highlighted.set((this.highlighted() + 1) % list.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.highlighted.set((this.highlighted() + list.length - 1) % list.length);
    } else if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault();
      this.choose(list[this.highlighted()]);
    } else if (event.key === 'Escape') {
      this.mention.set(null);
    }
  }

  choose(member: Member): void {
    const mention = this.mention();
    if (!mention) {
      return;
    }
    const el = this.area().nativeElement;
    const result = insertMention(this.text, mention, el.selectionStart ?? this.text.length, member.handle);
    this.text = result.text;
    el.value = result.text;
    el.setSelectionRange(result.cursor, result.cursor);
    el.focus();
    this.mention.set(null);
  }

  send(): void {
    const body = this.text.trim();
    if (body) {
      this.submitted.emit(body);
    }
  }

  clear(): void {
    this.text = '';
    this.area().nativeElement.value = '';
  }
}
