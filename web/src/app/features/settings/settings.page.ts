import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { Organisation } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { HolidaysCard } from './holidays.card';

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

  constructor() {
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

  timezones(current: string): string[] {
    return TIMEZONES.includes(current) ? TIMEZONES : [current, ...TIMEZONES];
  }

  saveProfile(): void {
    this.flash(this.http.patch('/api/me', { name: this.name }), 'Profile saved.', () => this.auth.loadMe().subscribe());
  }

  changePassword(): void {
    this.flash(
      this.http.post('/api/me/password', { currentPassword: this.currentPassword, newPassword: this.newPassword }),
      'Password changed.',
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
