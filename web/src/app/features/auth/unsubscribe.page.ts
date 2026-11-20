import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { InboxApi } from '../../core/inbox.api';
import { signedTokenFromFragment } from '../../core/link-token';
import { NotificationType } from '../../core/models';
import { NOTIFICATION_ABOUT } from '../settings/notification-labels';

/**
 * Where the stop link of a notification mail leads. It works without signing in. Nothing
 * changes until the button is pressed, so a mail scanner that opens the link does nothing.
 */
@Component({
  selector: 'app-unsubscribe-page',
  imports: [RouterLink],
  template: `
    <main class="auth-page">
      <div class="card auth-card">
        <h1 class="brand">Stop these mails</h1>
        @if (stopped(); as type) {
          <p role="status">You no longer get mails about {{ about[type] }}. They still show in the app.</p>
          <p class="muted small"><a routerLink="/settings">Change it in your settings</a></p>
        } @else if (token) {
          <p>The mails of this kind stop. You keep seeing them in the app, and can turn the mails on again in your settings.</p>
          @if (error()) {
            <p class="alert error" role="alert">{{ error() }}</p>
          }
          <button class="primary wide" type="button" [disabled]="busy()" (click)="stop()">{{ busy() ? 'Stopping…' : 'Stop these mails' }}</button>
        } @else {
          <p class="alert error" role="alert">This link is not complete. Open the link from the mail again.</p>
        }
      </div>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UnsubscribePage {
  private readonly inbox = inject(InboxApi);

  /** Read once and then taken out of the address bar. */
  protected readonly token = signedTokenFromFragment(inject(ActivatedRoute).snapshot.fragment);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly stopped = signal<NotificationType | null>(null);
  protected readonly about = NOTIFICATION_ABOUT;

  constructor() {
    inject(Location).replaceState('/unsubscribe');
  }

  stop(): void {
    if (!this.token) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.inbox.unsubscribe(this.token).subscribe({
      next: (res) => this.stopped.set(res.type),
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.busy.set(false);
      },
    });
  }
}
