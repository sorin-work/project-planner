import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('pp.token', 'secret-token');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adds the bearer token to API calls', () => {
    http.get('/api/me').subscribe();
    expect(backend.expectOne('/api/me').request.headers.get('Authorization')).toBe('Bearer secret-token');
  });

  it('does not send the token to the auth endpoints or elsewhere', () => {
    http.post('/api/auth/login', {}).subscribe();
    http.get('/assets/x.json').subscribe();
    expect(backend.expectOne('/api/auth/login').request.headers.has('Authorization')).toBe(false);
    expect(backend.expectOne('/assets/x.json').request.headers.has('Authorization')).toBe(false);
  });

  it('signs out when the token is rejected', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    http.get('/api/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/me').flush({ code: 'unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('keeps the session on other errors', () => {
    http.get('/api/projects/1').subscribe({ error: () => undefined });
    backend.expectOne('/api/projects/1').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(true);
  });
});
