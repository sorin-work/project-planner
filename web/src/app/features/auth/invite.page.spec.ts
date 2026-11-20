import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { InvitationLink } from '../../core/models';
import { InvitePage } from './invite.page';

const TOKEN = 'aB3_-xYz0123456789abcdefghijklmnopqrstuvwxy';
const link: InvitationLink = { email: 'nina@example.com', organisationName: 'Brightline Studio', role: 'MEMBER', hasAccount: false };
const session = (email: string) => ({ token: 't', expiresAt: '2999-01-01T00:00:00Z', user: { id: 5, email, name: 'N', handle: 'n' } });

describe('InvitePage', () => {
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function render(answer: InvitationLink) {
    TestBed.configureTestingModule({
      imports: [InvitePage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: `token=${TOKEN}` } } },
        { provide: Location, useValue: { replaceState: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(InvitePage);
    fixture.detectChanges();
    const read = http.expectOne('/api/auth/invitation');
    expect(read.request.body).toEqual({ token: TOKEN });
    read.flush(answer);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  beforeEach(() => localStorage.clear());

  it('signs up with the invited address and the token', async () => {
    const { fixture, el } = await render(link);
    expect(el.textContent).toContain('Brightline Studio');
    expect(el.querySelector('#email')).toBeNull();
    type(el, '#name', 'Nina');
    type(el, '#password', 'correct-horse');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));

    const signup = http.expectOne('/api/auth/signup');
    expect(signup.request.body).toEqual({ name: 'Nina', email: 'nina@example.com', password: 'correct-horse', invitationToken: TOKEN });
    signup.flush(session('nina@example.com'));
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('signs in and then accepts when the address has an account', async () => {
    const { fixture, el } = await render({ ...link, hasAccount: true });
    type(el, '#password', 'correct-horse');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));

    http.expectOne('/api/auth/login').flush(session('nina@example.com'));
    const accept = http.expectOne('/api/invitations/accept');
    expect(accept.request.body).toEqual({ token: TOKEN });
    accept.flush({ organisationId: 3, organisationName: 'Brightline Studio', role: 'MEMBER', currency: 'EUR' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('shows why a link does not work', async () => {
    TestBed.configureTestingModule({
      imports: [InvitePage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: `token=${TOKEN}` } } },
        { provide: Location, useValue: { replaceState: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(InvitePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/auth/invitation').flush(
      { status: 400, code: 'invalid_link', message: 'This invitation has expired, was cancelled or was already accepted.' },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain('expired');
  });
});
