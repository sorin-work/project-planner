import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { InboxApi } from '../../core/inbox.api';
import { OrgContext } from '../../core/org-context.service';
import { Avatar } from '../../shared/avatar';
import { ROLE_LABELS } from '../../shared/format';
import { TimerService, formatElapsed } from '../time/timer.service';

const UNREAD_POLL_MS = 60_000;

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Avatar],
  templateUrl: './shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContext);
  protected readonly timer = inject(TimerService);
  private readonly inbox = inject(InboxApi);
  private readonly router = inject(Router);

  protected readonly unread = signal(0);
  protected readonly loadError = signal<string | null>(null);
  protected readonly menuOpen = signal(false);
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly formatElapsed = formatElapsed;

  constructor() {
    this.auth.loadMe().subscribe({ error: (err: unknown) => this.loadError.set(messageOf(err)) });

    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.timer.refresh(orgId);
        this.refreshUnread();
      }
    });

    const poll = setInterval(() => this.refreshUnread(), UNREAD_POLL_MS);
    const navigation = this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.menuOpen.set(false);
      this.refreshUnread();
    });
    inject(DestroyRef).onDestroy(() => {
      clearInterval(poll);
      navigation.unsubscribe();
    });
  }

  stopTimer(): void {
    const orgId = this.org.orgId();
    if (orgId !== null) {
      this.timer.stop(orgId).subscribe();
    }
  }

  logout(): void {
    this.auth.logout();
  }

  private refreshUnread(): void {
    const orgId = this.org.orgId();
    if (orgId === null) {
      return;
    }
    this.inbox.unreadCount(orgId).subscribe({ next: (res) => this.unread.set(res.count), error: () => undefined });
  }
}
