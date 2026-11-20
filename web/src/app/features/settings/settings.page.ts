import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { InboxApi } from '../../core/inbox.api';
import { Delivery, NotificationPreferences, NotificationType, Organisation } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { HolidaysCard } from './holidays.card';
import { DELIVERY_LABELS, NOTIFICATION_LABELS } from './notification-labels';

const TIMEZONES = ['UTC', 'Europe/London', 'Europe/Berlin', 'Europe/Bucharest', 'America/New_York', 'America/Los_Angeles', 'Asia/Singapore'];
const CURRENCIES = ['EUR', 'GBP', 'USD', 'RON', 'CHF', 'SEK'];

@Component({
  selector: 'app-settings-page',
  imports: [FormsModule, HolidaysCard],
  template: `
    <h1>Settings</h1>
    @if (message()) {
      <p class="alert success" role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="grid-2">
      <section class="card">
        <h2>Profile</h2>
        <form class="form-grid" (ngSubmit)="saveProfile()">
          <div class="field span-2"><label for="s-name">Name</label><input id="s-name" name="name" [(ngModel)]="name" required /></div>
          <p class="muted small span-2">Email {{ auth.user()?.email }} · mention handle &#64;{{ auth.user()?.handle }}</p>
          <div class="actions span-2"><button type="submit" class="primary">Save profile</button></div>
        </form>
        <h3>Change password</h3>
        <form class="form-grid" (ngSubmit)="changePassword()">
          <div class="field span-2"><label for="s-current">Current password</label>
            <input id="s-current" name="current" type="password" autocomplete="current-password" [(ngModel)]="currentPassword" /></div>
          <div class="field span-2"><label for="s-new">New password</label>
            <input id="s-new" name="new" type="password" autocomplete="new-password" minlength="8" [(ngModel)]="newPassword" /></div>
          <div class="actions span-2"><button type="submit" [disabled]="newPassword.length < 8">Change password</button></div>
        </form>
      </section>
      @if (organisation(); as o) {
        <section class="card">
          <h2>Organisation</h2>
          <form class="form-grid" (ngSubmit)="saveOrg()">
            <div class="field span-2"><label for="o-name">Name</label>
              <input id="o-name" name="orgName" [(ngModel)]="o.name" [disabled]="!org.isOwner()" /></div>
            <div class="field"><label for="o-currency">Currency</label>
              <select id="o-currency" name="currency" [(ngModel)]="o.currency" [disabled]="!org.isOwner()">
                @for (c of currencies; track c) { <option [value]="c">{{ c }}</option> }
              </select></div>
            <div class="field"><label for="o-tz">Time zone</label>
              <select id="o-tz" name="timezone" [(ngModel)]="o.timezone" [disabled]="!org.isOwner()">
                @for (z of timezones(o.timezone); track z) { <option [value]="z">{{ z }}</option> }
              </select></div>
            @if (org.canManage()) {
              <div class="field"><label for="o-rate">Default hourly rate</label>
                <input id="o-rate" name="rate" type="number" min="0" [(ngModel)]="o.defaultHourlyRate" [disabled]="!org.isOwner()" /></div>
            }
            @if (org.isOwner()) {
              <div class="actions span-2"><button type="submit" class="primary">Save organisation</button></div>
            } @else {
              <p class="muted small span-2">Only owners can change these.</p>
            }
          </form>
        </section>
      }
      @if (org.canManage()) {
        <app-holidays-card />
      }
    </div>
    @if (preferences(); as p) {
      <section class="card" id="notifications">
        <h2>Notifications</h2>
        @if (!p.mailAvailable) {
          <p class="muted small">This server sends no mail, so what you choose to get by mail shows in the app only.</p>
        }
        <table class="table">
          <tbody>
            @for (pref of p.preferences; track pref.type) {
              <tr>
                <th scope="row">{{ labels[pref.type] }}</th>
                <td>
                  <select [attr.aria-label]="labels[pref.type]" [value]="pref.delivery"
                    (change)="setDelivery(pref.type, $any($event.target).value)">
                    @for (d of deliveries; track d) {
                      <option [value]="d" [selected]="d === pref.delivery">{{ deliveryLabels[d] }}</option>
                    }
                  </select>
                </td>
              </tr>
            }
          </tbody>
        </table>
        <p class="muted small">This holds for every organisation you are in.</p>
      </section>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPage {
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContext);
  private readonly orgApi = inject(OrgApi);
  private readonly http = inject(HttpClient);

  protected readonly organisation = signal<Organisation | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected name = '';
  protected currentPassword = '';
  protected newPassword = '';
  protected readonly currencies = CURRENCIES;
  private readonly inbox = inject(InboxApi);
  protected readonly preferences = signal<NotificationPreferences | null>(null);
  protected readonly labels = NOTIFICATION_LABELS;
  protected readonly deliveryLabels = DELIVERY_LABELS;
  protected readonly deliveries: Delivery[] = ['EMAIL', 'IN_APP', 'OFF'];

  constructor() {
    this.inbox.preferences().subscribe({ next: (p) => this.preferences.set(p) });
    effect(() => {
      this.name = this.auth.user()?.name ?? '';
    });
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId !== null) {
        this.orgApi.organisation(orgId).subscribe({ next: (o) => this.organisation.set({ ...o }) });
      }
    });
  }

  setDelivery(type: NotificationType, delivery: Delivery): void {
    this.message.set(null);
    this.error.set(null);
    this.inbox.setPreferences([{ type, delivery }]).subscribe({
      next: (p) => {
        this.preferences.set(p);
        this.message.set('Notifications saved.');
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  timezones(current: string): string[] {
    return TIMEZONES.includes(current) ? TIMEZONES : [current, ...TIMEZONES];
  }

  saveProfile(): void {
    this.flash(this.http.patch('/api/me', { name: this.name }), 'Profile saved.', () => this.auth.loadMe().subscribe());
  }

  changePassword(): void {
    // The answer carries a new token: the old one, and every other session, stops working.
    this.flash(
      this.auth.changePassword(this.currentPassword, this.newPassword),
      'Password changed. Everywhere else you were signed in, you are signed out.',
      () => {
        this.currentPassword = '';
        this.newPassword = '';
      },
    );
  }

  saveOrg(): void {
    const o = this.organisation();
    if (!o) {
      return;
    }
    this.flash(
      this.orgApi.updateOrganisation(o.id, {
        name: o.name,
        currency: o.currency,
        timezone: o.timezone,
        defaultHourlyRate: o.defaultHourlyRate,
      }),
      'Organisation saved.',
      () => this.auth.loadMe().subscribe(),
    );
  }

  private flash(request: ReturnType<HttpClient['get']>, success: string, then?: () => void): void {
    this.message.set(null);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.message.set(success);
        then?.();
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}
