import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ForgotPage } from './forgot.page';

describe('ForgotPage', () => {
  it('sends the address and says the same thing whatever the answer means', async () => {
    TestBed.configureTestingModule({
      imports: [ForgotPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const fixture = TestBed.createComponent(ForgotPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const input = el.querySelector<HTMLInputElement>('#email')!;
    input.value = 'someone@example.com';
    input.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));

    const request = TestBed.inject(HttpTestingController).expectOne('/api/auth/forgot');
    expect(request.request.body).toEqual({ email: 'someone@example.com' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('If there is an account for that address');
  });
});
