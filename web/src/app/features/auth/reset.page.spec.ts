import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { ResetPage } from './reset.page';

const TOKEN = 'aB3_-xYz0123456789abcdefghijklmnopqrstuvwxy';

describe('ResetPage', () => {
  let replaceState: ReturnType<typeof vi.fn>;

  function render(fragment: string | null) {
    replaceState = vi.fn();
    TestBed.configureTestingModule({
      imports: [ResetPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
        { provide: Location, useValue: { replaceState } },
      ],
    });
    const fixture = TestBed.createComponent(ResetPage);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
  }

  function type(el: HTMLElement, value: string): void {
    const input = el.querySelector<HTMLInputElement>('#password')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('reads the token from after the #, takes it out of the address and sends it with the password', async () => {
    const { fixture, el, http } = render(`token=${TOKEN}`);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    expect(replaceState).toHaveBeenCalledWith('/reset');

    type(el, 'brand-new-password');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/auth/reset');
    expect(request.request.body).toEqual({ token: TOKEN, password: 'brand-new-password' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reset: 'done' } });
  });

  it('shows the message for a link that no longer works', async () => {
    const { fixture, el, http } = render(`token=${TOKEN}`);
    type(el, 'brand-new-password');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/auth/reset').flush(
      { status: 400, code: 'invalid_link', message: 'This link has expired or was already used. Ask for a new one.' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('expired');
  });

  it('asks for nothing without a token', () => {
    const { el, http } = render(null);
    expect(el.querySelector('#password')).toBeNull();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('not complete');
    http.verify();
  });
});
