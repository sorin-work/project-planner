import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';

@Component({
  selector: 'app-forgot-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <form class="card auth-card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <h1 class="brand">Forgot your password?</h1>
        @if (sent()) {
          <p role="status">
            If there is an account for that address, a link to choose a new password is on its way. It works for one hour.
          </p>
        } @else {
          <p class="muted">Enter the address you sign in with and we will mail you a link to choose a new password.</p>
          <label for="email">Email</label>
          <input id="email" type="email" formControlName="email" autocomplete="username" />
          @if (form.controls.email.touched && form.controls.email.invalid) {
            <p class="field-error">Enter a valid email address</p>
          }
          @if (error()) {
            <p class="alert error" role="alert">{{ error() }}</p>
          }
          <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Sending…' : 'Send the link' }}</button>
        }
        <p class="muted small"><a routerLink="/login">Back to sign in</a></p>
      </form>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForgotPage {
  private readonly auth = inject(AuthService);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.auth.forgot(this.form.getRawValue().email).subscribe({
      next: () => this.sent.set(true),
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.busy.set(false);
      },
    });
  }
}
