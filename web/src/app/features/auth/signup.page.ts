import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { fieldErrors, messageOf } from '../../core/errors';

@Component({
  selector: 'app-signup-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="auth-page">
      <form class="card auth-card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <h1 class="brand">Create your account</h1>

        <label for="name">Your name</label>
        <input id="name" formControlName="name" autocomplete="name" />
        @if (show('name')) {
          <p class="field-error">{{ serverErrors()['name'] || 'Enter your name' }}</p>
        }

        <label for="email">Work email</label>
        <input id="email" type="email" formControlName="email" autocomplete="email" />
        @if (show('email')) {
          <p class="field-error">{{ serverErrors()['email'] || 'Enter a valid email address' }}</p>
        }

        <label for="password">Password</label>
        <input id="password" type="password" formControlName="password" autocomplete="new-password" />
        @if (show('password')) {
          <p class="field-error">{{ serverErrors()['password'] || 'Use at least 8 characters' }}</p>
        }

        <label for="organisationName">Team or agency name <span class="muted">(optional)</span></label>
        <input id="organisationName" formControlName="organisationName" placeholder="e.g. Brightline Studio" />

        @if (error()) {
          <p class="alert error" role="alert">{{ error() }}</p>
        }
        <button class="primary wide" type="submit" [disabled]="busy()">{{ busy() ? 'Creating…' : 'Create account' }}</button>
        <p class="muted small">Already have an account? <a routerLink="/login">Sign in</a></p>
      </form>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignupPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    organisationName: [''],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly serverErrors = signal<Record<string, string>>({});

  show(field: 'name' | 'email' | 'password'): boolean {
    const control = this.form.controls[field];
    return (control.touched && control.invalid) || !!this.serverErrors()[field];
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.serverErrors.set({});
    const value = this.form.getRawValue();
    this.auth
      .signup({ ...value, organisationName: value.organisationName.trim() || undefined })
      .subscribe({
        next: () => void this.router.navigateByUrl('/'),
        error: (err: unknown) => {
          const fields = fieldErrors(err);
          this.serverErrors.set(fields);
          this.error.set(Object.keys(fields).length ? null : messageOf(err));
          this.busy.set(false);
        },
      });
  }
}
