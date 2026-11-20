import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { fieldErrors, messageOf } from '../../core/errors';
import { tokenFromFragment } from '../../core/link-token';

@Component({
  selector: 'app-reset-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <form class="card auth-card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <h1 class="brand">Choose a new password</h1>
        @if (token) {
          <p class="muted">Every place you are signed in will be signed out, this browser too.</p>
          <label for="password">New password</label>
          <input id="password" type="password" formControlName="password" autocomplete="new-password" />
          @if ((form.controls.password.touched && form.controls.password.invalid) || serverErrors()['password']) {
            <p class="field-error">{{ serverErrors()['password'] || 'Use at least 8 characters' }}</p>
          }
          @if (error()) {
            <p class="alert error" role="alert">{{ error() }}</p>
          }
          <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Saving…' : 'Set the password' }}</button>
        } @else {
          <p class="alert error" role="alert">This link is not complete. Open the link from the mail again, or ask for a new one.</p>
        }
        <p class="muted small"><a routerLink="/forgot">Ask for a new link</a> · <a routerLink="/login">Sign in</a></p>
      </form>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResetPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Read once and then taken out of the address bar. */
  protected readonly token = tokenFromFragment(inject(ActivatedRoute).snapshot.fragment);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly serverErrors = signal<Record<string, string>>({});

  constructor() {
    inject(Location).replaceState('/reset');
  }

  submit(): void {
    if (!this.token || this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.serverErrors.set({});
    this.auth.reset(this.token, this.form.getRawValue().password).subscribe({
      next: () => void this.router.navigate(['/login'], { queryParams: { reset: 'done' } }),
      error: (err: unknown) => {
        const fields = fieldErrors(err);
        this.serverErrors.set(fields);
        this.error.set(Object.keys(fields).length ? null : messageOf(err));
        this.busy.set(false);
      },
    });
  }
}
