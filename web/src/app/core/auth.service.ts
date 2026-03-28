import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AuthResponse, Me, User } from './models';

const TOKEN_KEY = 'pp.token';
const EXPIRES_KEY = 'pp.expiresAt';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly tokenState = signal<string | null>(readToken());
  readonly me = signal<Me | null>(null);

  readonly token = this.tokenState.asReadonly();
  readonly isLoggedIn = computed(() => this.tokenState() !== null);
  readonly user = computed<User | null>(() => this.me()?.user ?? null);

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/login', { email, password })
      .pipe(tap((res) => this.store(res)));
  }

  signup(body: {
    name: string;
    email: string;
    password: string;
    organisationName?: string;
  }): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/auth/signup', body).pipe(tap((res) => this.store(res)));
  }

  loadMe(): Observable<Me> {
    return this.http.get<Me>('/api/me').pipe(tap((me) => this.me.set(me)));
  }

  logout(redirect = true): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(EXPIRES_KEY);
    this.tokenState.set(null);
    this.me.set(null);
    if (redirect) {
      void this.router.navigate(['/login']);
    }
  }

  private store(res: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(EXPIRES_KEY, res.expiresAt);
    this.tokenState.set(res.token);
  }
}

/** The saved token, unless it has already expired. */
function readToken(): string | null {
  try {
    const token = localStorage.getItem(TOKEN_KEY);
    const expires = localStorage.getItem(EXPIRES_KEY);
    if (!token) {
      return null;
    }
    if (expires && Date.parse(expires) <= Date.now()) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(EXPIRES_KEY);
      return null;
    }
    return token;
  } catch {
    return null;
  }
}
