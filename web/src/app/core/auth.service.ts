import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, of, shareReplay, tap } from 'rxjs';
import { AuthOptions, AuthResponse, InvitationLink, Me, MembershipSummary, User } from './models';
import { forgetUser } from './user-storage';

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

  /**
   * Whether the server sends mail, asked once. When the question fails the links that
   * need mail stay hidden, which is what the app looked like before it had mail.
   */
  readonly options$: Observable<AuthOptions> = this.http.get<AuthOptions>('/api/auth/options').pipe(
    catchError(() => of({ mail: false, inviteLinkRequired: false })),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

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
    invitationToken?: string;
  }): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/auth/signup', body).pipe(tap((res) => this.store(res)));
  }

  /** Answers the same whether or not the address has an account. */
  forgot(email: string): Observable<void> {
    return this.http.post<void>('/api/auth/forgot', { email });
  }

  reset(token: string, password: string): Observable<void> {
    return this.http.post<void>('/api/auth/reset', { token, password });
  }

  readInvitation(token: string): Observable<InvitationLink> {
    return this.http.post<InvitationLink>('/api/auth/invitation', { token });
  }

  acceptInvitation(token: string): Observable<MembershipSummary> {
    return this.http.post<MembershipSummary>('/api/invitations/accept', { token });
  }

  /** Every other session ends. The answer carries the token this one goes on with. */
  changePassword(currentPassword: string, newPassword: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/me/password', { currentPassword, newPassword })
      .pipe(tap((res) => this.store(res)));
  }

  loadMe(): Observable<Me> {
    return this.http.get<Me>('/api/me').pipe(tap((me) => this.me.set(me)));
  }

  logout(redirect = true): void {
    const user = this.user();
    try {
      if (user) {
        // What was kept for this person on this browser goes with them.
        forgetUser(localStorage, user.id);
      }
    } catch {
      // Private mode: there is nothing to remove.
    }
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
