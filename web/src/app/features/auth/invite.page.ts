import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, map, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { fieldErrors, messageOf } from '../../core/errors';
import { tokenFromFragment } from '../../core/link-token';
import { InvitationLink } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ROLE_LABELS } from '../../shared/format';

/**
 * Where the link in an invitation mail leads. The invitation fixes the address, the
 * organisation and the role. Signed in with that address: accept. With an account for it:
 * sign in, then accept. Without: sign up with the link.
 */
@Component({
  selector: 'app-invite-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <form class="card auth-card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <h1 class="brand">Join an organisation</h1>
        @if (link(); as l) {
          <p>
            You are invited to <strong>{{ l.organisationName }}</strong> as {{ roleLabels[l.role].toLowerCase() }}, with
            <strong>{{ l.email }}</strong>.
          </p>
          @switch (mode()) {
            @case ('accept') {
              <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Joining…' : 'Accept' }}</button>
            }
            @case ('other-account') {
              <p class="alert error" role="alert">
                You are signed in as {{ auth.user()?.email }}. The invitation is for {{ l.email }} only.
              </p>
              <button class="wide" type="button" (click)="signOut()">Sign out and use {{ l.email }}</button>
            }
            @case ('sign-in') {
              <label for="password">Password of {{ l.email }}</label>
              <input id="password" type="password" formControlName="password" autocomplete="current-password" />
              <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Joining…' : 'Sign in and accept' }}</button>
              <p class="muted small"><a routerLink="/forgot">Forgot your password?</a></p>
            }
            @case ('sign-up') {
              <label for="name">Your name</label>
              <input id="name" formControlName="name" autocomplete="name" />
              @if (serverErrors()['name']) {
                <p class="field-error">{{ serverErrors()['name'] }}</p>
              }
              <label for="password">Choose a password</label>
              <input id="password" type="password" formControlName="password" autocomplete="new-password" />
              @if (serverErrors()['password']) {
                <p class="field-error">{{ serverErrors()['password'] }}</p>
              }
              <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Joining…' : 'Create account and join' }}</button>
            }
          }
        } @else if (!error()) {
          <p class="muted" role="status">Reading the invitation…</p>
        }
        @if (error()) {
          <p class="alert error" role="alert">{{ error() }}</p>
        }
        <p class="muted small"><a routerLink="/">Go to the app</a></p>
      </form>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitePage {
  protected readonly auth = inject(AuthService);
  private readonly org = inject(OrgContext);
  private readonly router = inject(Router);

  /** Read once and then taken out of the address bar. */
  private readonly token = tokenFromFragment(inject(ActivatedRoute).snapshot.fragment);

  protected readonly link = signal<InvitationLink | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly serverErrors = signal<Record<string, string>>({});
  protected readonly roleLabels = ROLE_LABELS;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.maxLength(120)]],
    password: [''],
  });

  protected readonly mode = computed<'accept' | 'other-account' | 'sign-in' | 'sign-up'>(() => {
    const link = this.link();
    if (this.auth.isLoggedIn()) {
      return this.auth.user()?.email === link?.email ? 'accept' : 'other-account';
    }
    return link?.hasAccount ? 'sign-in' : 'sign-up';
  });

  constructor() {
    inject(Location).replaceState('/invite');
    if (!this.token) {
      this.error.set('This link is not complete. Open the link from the mail again.');
      return;
    }
    if (this.auth.isLoggedIn() && !this.auth.user()) {
      this.auth.loadMe().subscribe({ error: () => undefined });
    }
    this.auth.readInvitation(this.token).subscribe({
      next: (link) => this.link.set(link),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  signOut(): void {
    this.auth.logout(false);
  }

  submit(): void {
    const link = this.link();
    const token = this.token;
    if (!link || !token || this.busy() || this.mode() === 'other-account') {
      return;
    }
    const { name, password } = this.form.getRawValue();
    let joined: Observable<{ organisationId: number } | null>;
    switch (this.mode()) {
      case 'accept':
        joined = this.auth.acceptInvitation(token);
        break;
      case 'sign-in':
        joined = this.auth.login(link.email, password).pipe(switchMap(() => this.auth.acceptInvitation(token)));
        break;
      default:
        // A new account is only in the organisations it was invited to.
        joined = this.auth.signup({ name, email: link.email, password, invitationToken: token }).pipe(map(() => null));
    }
    this.busy.set(true);
    this.error.set(null);
    this.serverErrors.set({});
    joined.subscribe({
      next: (membership) => {
        if (membership?.organisationId) {
          this.org.select(membership.organisationId);
        }
        void this.router.navigateByUrl('/');
      },
      error: (err: unknown) => {
        const fields = fieldErrors(err);
        this.serverErrors.set(fields);
        this.error.set(Object.keys(fields).length ? null : messageOf(err));
        this.busy.set(false);
      },
    });
  }
}
