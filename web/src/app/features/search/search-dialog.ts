import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { SearchHit, SearchHitType } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { SearchApi } from '../../core/search.api';
import { moveHighlight } from '../../shared/list-keys';
import { MIN_SEARCH_LENGTH, QuickSearch } from './quick-search';
import { loadRecent, recentKey, rememberPick } from './recent-picks';

interface Option {
  hit: SearchHit;
  index: number;
  id: string;
}

interface Group {
  label: string;
  options: Option[];
}

const KINDS: { type: SearchHitType; label: string }[] = [
  { type: 'PROJECT', label: 'Projects' },
  { type: 'TASK', label: 'Tasks' },
  { type: 'COMMENT', label: 'Comments' },
];

/** Where a hit leads: a project to its page, a task or a comment to the task, opened in the list. */
export function hitTarget(hit: SearchHit): { commands: (string | number)[]; queryParams?: { task: number } } {
  return hit.taskId === undefined
    ? { commands: ['/projects', hit.projectId] }
    : { commands: ['/projects', hit.projectId, 'list'], queryParams: { task: hit.taskId } };
}

function storage(): Storage | null {
  try {
    return localStorage;
  } catch {
    return null;
  }
}

/**
 * The search dialog: a text field over a list of hits. Focus stays in the field. The
 * arrow keys move a highlight that screen readers follow through aria-activedescendant.
 */
@Component({
  selector: 'app-search-dialog',
  host: { '(keydown)': 'onKeydown($event)' },
  template: `
    <dialog #dialog class="search-dialog" aria-label="Search" (cancel)="$event.preventDefault(); close()" (close)="closed()"
      (mousedown)="onBackdrop($event)">
      <div class="search-panel">
        <div class="search-field">
          <input #input type="text" role="combobox" aria-label="Search projects, tasks and comments" aria-autocomplete="list"
            aria-controls="search-results" [attr.aria-expanded]="options().length > 0" [attr.aria-activedescendant]="activeId()"
            placeholder="Find a project, a task or a comment" autocomplete="off" spellcheck="false" (input)="onInput()" />
          <button type="button" class="icon-button" aria-label="Close search" (click)="close()">✕</button>
        </div>
        <p class="sr-only" role="status">{{ announcement() }}</p>
        <div id="search-results" class="search-results" role="listbox" aria-label="Results" [class.stale]="quick.waiting()">
          @for (group of groups(); track group.label) {
            <div role="group" [attr.aria-label]="group.label">
              <p class="search-group" aria-hidden="true">{{ group.label }}</p>
              @for (option of group.options; track option.id) {
                <div role="option" class="search-option" [id]="option.id" [attr.aria-selected]="option.index === highlighted()"
                  [class.active]="option.index === highlighted()" (mousedown)="$event.preventDefault(); go(option.hit)">
                  <span class="code">{{ option.hit.projectCode }}</span>
                  <span class="search-title">{{ option.hit.title }}</span>
                  @if (option.hit.snippet) {
                    <span class="muted search-snippet">{{ option.hit.snippet }}</span>
                  }
                </div>
              }
            </div>
          }
        </div>
        @if (message(); as text) {
          <p class="search-message" [class.error]="quick.error()">{{ text }}</p>
        }
        <p class="search-hint muted small" aria-hidden="true">↑ ↓ to move · Enter to open · Esc to close</p>
      </div>
    </dialog>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SearchDialog {
  private readonly api = inject(SearchApi);
  private readonly org = inject(OrgContext);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');

  readonly isOpen = signal(false);
  protected readonly text = signal('');
  protected readonly highlighted = signal(0);
  private readonly recent = signal<SearchHit[]>([]);
  /** What had the focus when the dialog opened, to give it back. */
  private opener: HTMLElement | null = null;

  protected readonly quick = new QuickSearch(
    (text) => this.api.search(this.org.orgId() ?? 0, text),
    (hit) => this.go(hit),
  );

  /** Before anything is typed, the last picks. After that, the hits by kind. */
  private readonly typing = computed(() => this.text().length >= MIN_SEARCH_LENGTH);

  protected readonly groups = computed<Group[]>(() => {
    const results = this.quick.results();
    const lists = this.typing()
      ? results
        ? [results.projects, results.tasks, results.comments].map((hits, i) => ({ label: KINDS[i].label, hits }))
        : []
      : this.text() === ''
        ? [{ label: 'Recent', hits: this.recent() }]
        : [];
    let index = 0;
    return lists
      .filter((list) => list.hits.length > 0)
      .map((list) => ({
        label: list.label,
        options: list.hits.map((hit) => ({ hit, index, id: `search-option-${index++}` })),
      }));
  });

  protected readonly options = computed(() => this.groups().flatMap((group) => group.options));
  protected readonly activeId = computed(() => this.options()[this.highlighted()]?.id ?? null);

  /** What stands in place of the list when there is no list. */
  protected readonly message = computed(() => {
    if (this.quick.error()) {
      return this.quick.error();
    }
    if (this.options().length > 0) {
      return null;
    }
    if (this.text() === '') {
      return null;
    }
    if (!this.typing()) {
      return 'Type at least two characters.';
    }
    return this.quick.waiting() ? 'Searching…' : `Nothing found for "${this.text()}".`;
  });

  /** For screen readers, once the list is the answer to the text as typed. */
  protected readonly announcement = computed(() => {
    if (!this.isOpen() || this.quick.waiting() || this.quick.error()) {
      return '';
    }
    const count = this.options().length;
    if (!this.typing()) {
      return count > 0 ? `${count} recent` : '';
    }
    return count === 0 ? 'No results' : count === 1 ? '1 result' : `${count} results`;
  });

  open(): void {
    const orgId = this.org.orgId();
    const user = this.auth.user();
    if (this.isOpen() || orgId === null || !user) {
      return;
    }
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.quick.reset();
    this.text.set('');
    this.highlighted.set(0);
    this.recent.set(loadRecent(storage(), recentKey(user.id, orgId)));

    const dialog = this.dialog().nativeElement;
    // showModal keeps the page behind out of reach. Where it is missing the dialog still opens.
    if (typeof dialog.showModal === 'function') {
      dialog.showModal();
    } else {
      dialog.setAttribute('open', '');
    }
    this.isOpen.set(true);
    const input = this.input().nativeElement;
    input.value = '';
    input.focus();
  }

  close(): void {
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.close === 'function' && dialog.open) {
      dialog.close();
    } else {
      dialog.removeAttribute('open');
    }
    this.closed();
  }

  /** Also called when the browser closes the dialog by itself. */
  protected closed(): void {
    if (!this.isOpen()) {
      return;
    }
    this.isOpen.set(false);
    this.quick.reset();
    this.opener?.focus();
    this.opener = null;
  }

  protected onInput(): void {
    const value = this.input().nativeElement.value;
    this.text.set(value.trim());
    this.highlighted.set(0);
    this.quick.type(value);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.isOpen()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close();
    } else if (event.key === 'Tab') {
      this.keepFocusInside(event);
    } else if (event.target === this.input().nativeElement) {
      this.onFieldKey(event);
    }
  }

  private onFieldKey(event: KeyboardEvent): void {
    const options = this.options();
    const moved = moveHighlight(event.key, this.highlighted(), options.length);
    if (moved !== null) {
      event.preventDefault();
      this.highlighted.set(moved);
      document.getElementById(options[moved].id)?.scrollIntoView?.({ block: 'nearest' });
    } else if (event.key === 'Enter' && !event.isComposing) {
      event.preventDefault();
      const highlighted = options[this.highlighted()]?.hit;
      if (this.typing()) {
        this.quick.enter(highlighted);
      } else if (highlighted) {
        this.go(highlighted);
      }
    }
  }

  /** Tab goes round the controls of the dialog and never to the page behind it. */
  private keepFocusInside(event: KeyboardEvent): void {
    const controls = [...this.dialog().nativeElement.querySelectorAll<HTMLElement>('input, button')];
    const at = controls.indexOf(document.activeElement as HTMLElement);
    const next = (at + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
    event.preventDefault();
    controls[next].focus();
  }

  /** A press on the dimmed page around the panel closes the dialog. */
  protected onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) {
      this.close();
    }
  }

  protected go(hit: SearchHit): void {
    const orgId = this.org.orgId();
    const user = this.auth.user();
    if (orgId !== null && user) {
      rememberPick(storage(), recentKey(user.id, orgId), hit);
    }
    this.close();
    const target = hitTarget(hit);
    void this.router.navigate(target.commands, { queryParams: target.queryParams });
  }
}
