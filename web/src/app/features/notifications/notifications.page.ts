import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { InboxApi } from '../../core/inbox.api';
import { Notification } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { timeAgo } from '../../shared/dates';

@Component({
  selector: 'app-notifications-page',
  template: `
    <div class="page-header">
      <h1>Notifications</h1>
      <div>
      </div>
    </div>
    <section class="card">
      <ul class="notifications">
        @for (n of items(); track n.id) {
          <li [class.unread]="!n.read">
            <button type="button" class="row-button" (click)="open(n)">
              <span class="grow">{{ n.message }}</span>
              <span class="muted small">{{ timeAgo(n.createdAt) }}</span>
            </button>
          </li>
        } @empty {
          <li class="muted">You're all caught up.</li>
        }
      </ul>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage {
  private readonly api = inject(InboxApi);
  private readonly org = inject(OrgContext);
  private readonly router = inject(Router);

  protected readonly items = signal<Notification[]>([]);
  protected readonly unreadOnly = signal(false);
  protected readonly timeAgo = timeAgo;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      const unread = this.unreadOnly();
      if (orgId !== null) {
        this.api.notifications(orgId, unread).subscribe({ next: (n) => this.items.set(n) });
      }
    });
  }

  open(n: Notification): void {
    if (!n.read) {
      this.api.markRead(n.id).subscribe();
      this.items.update((all) => all.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
    }
    if (n.projectId && n.taskId) {
      void this.router.navigate(['/projects', n.projectId, 'list'], { queryParams: { task: n.taskId } });
    } else if (n.type === 'TIMESHEET_SUBMITTED') {
      void this.router.navigate(['/approvals']);
    } else if (n.type.startsWith('TIMESHEET_')) {
      void this.router.navigate(['/time']);
    }
  }
}
