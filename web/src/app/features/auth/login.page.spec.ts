import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { LoginPage } from './login.page';

describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  async function render(mail = false) {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/auth/options').flush({ mail, inviteLinkRequired: false });
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('shows validation messages instead of sending an empty form', async () => {
    const { fixture, el } = await render();
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(el.textContent).toContain('Enter a valid email address');
    TestBed.inject(HttpTestingController).verify();
  });

  it('signs in and goes to the dashboard', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const { fixture, el } = await render();
    type(el, '#email', 'demo@example.com');
    type(el, '#password', 'demo-password');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/login')
      .flush({ token: 't', expiresAt: '2999-01-01T00:00:00Z', user: { id: 1, email: 'demo@example.com', name: 'Demo', handle: 'demo' } });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('shows the server message for bad credentials', async () => {
    const { fixture, el } = await render();
    type(el, '#email', 'demo@example.com');
    type(el, '#password', 'nope');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/login')
      .flush({ status: 401, code: 'invalid_credentials', message: 'Email or password is incorrect' }, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Email or password is incorrect');
  });

  it('says how long to wait after too many wrong passwords, and lets the person try again', async () => {
    const { fixture, el } = await render();
    type(el, '#email', 'demo@example.com');
    type(el, '#password', 'nope');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/login')
      .flush(
        { status: 429, code: 'too_many_attempts', message: 'Too many wrong passwords. Try again in 2 minutes.' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '120' } },
      );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Try again in 2 minutes.');
    expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
  });

  it('offers the reset link only when the server sends mail', async () => {
    const without = await render(false);
    expect(without.el.querySelector('a[href="/forgot"]')).toBeNull();
    without.fixture.destroy();
  });

  it('shows the reset link when the server sends mail, and says when a new password was set', async () => {
    const { fixture, el } = await render(true);
    fixture.componentRef.setInput('reset', 'done');
    await fixture.whenStable();
    expect(el.querySelector('a[href="/forgot"]')?.textContent).toContain('Forgot your password?');
    expect(el.querySelector('[role=status]')?.textContent).toContain('Your password was changed');
  });
});
