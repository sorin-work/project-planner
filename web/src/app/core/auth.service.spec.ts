import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let http: HttpTestingController;

  function setup(): AuthService {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => http.verify());

  it('stores the token after signing in', () => {
    const auth = setup();
    auth.login('demo@example.com', 'demo-password').subscribe();
    const req = http.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ email: 'demo@example.com', password: 'demo-password' });
    req.flush({ token: 'abc', expiresAt: '2999-01-01T00:00:00Z', user: { id: 1, email: 'demo@example.com', name: 'Demo', handle: 'demo' } });
    expect(auth.isLoggedIn()).toBe(true);
    expect(localStorage.getItem('pp.token')).toBe('abc');
  });

  it('forgets everything on logout', () => {
    localStorage.setItem('pp.token', 'abc');
    const auth = setup();
    auth.loadMe().subscribe();
    http.expectOne('/api/me').flush({ user: { id: 1, email: 'a@b.c', name: 'A', handle: 'a' }, organisations: [] });
    expect(auth.user()?.name).toBe('A');
    auth.logout(false);
    expect(auth.isLoggedIn()).toBe(false);
    expect(auth.user()).toBeNull();
    expect(localStorage.getItem('pp.token')).toBeNull();
  });
});
