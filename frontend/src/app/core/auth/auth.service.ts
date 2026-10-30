import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, retry, shareReplay, tap, throwError, timer } from 'rxjs';
import { API_BASE, ApiResponse } from '../api.service';
import { AuthTokenResponse, RegisterCustomerPayload, RegisterDriverPayload, RegistrationResponse, User, UserRole } from './auth.models';

/**
 * Holds the access token in memory only (never localStorage). The refresh token lives in an HttpOnly cookie the
 * browser sends to /api/v1/auth/*; JavaScript can neither read nor write it. On page load the session is restored
 * by calling /auth/refresh once.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  /** Required by the backend on cookie endpoints as CSRF defence. */
  private readonly cookieHeaders = new HttpHeaders({ 'X-Requested-With': 'XMLHttpRequest' });
  private readonly token = signal<string | null>(null);
  private readonly currentUser = signal<User | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.token() !== null && this.currentUser() !== null);
  readonly role = computed<UserRole | null>(() => this.currentUser()?.role ?? null);

  accessToken(): string | null { return this.token(); }

  login(identifier: string, password: string): Observable<User> {
    return this.http.post<ApiResponse<AuthTokenResponse>>(`${API_BASE}/auth/login`, { identifier, password }, { withCredentials: true })
      .pipe(map(response => this.applySession(response.data)));
  }

  registerCustomer(payload: RegisterCustomerPayload): Observable<RegistrationResponse> {
    return this.http.post<ApiResponse<RegistrationResponse>>(`${API_BASE}/auth/register/customer`, payload).pipe(map(r => r.data));
  }

  registerDriver(payload: RegisterDriverPayload): Observable<RegistrationResponse> {
    return this.http.post<ApiResponse<RegistrationResponse>>(`${API_BASE}/auth/register/driver`, payload).pipe(map(r => r.data));
  }

  /**
   * Exchanges the refresh cookie for a new access token. Concurrent callers share one request. A single retry on 401
   * covers the case where another tab rotated the cookie a moment earlier.
   */
  refresh(): Observable<string> {
    this.refreshInFlight ??= this.http.post<ApiResponse<AuthTokenResponse>>(`${API_BASE}/auth/refresh`, null, { withCredentials: true, headers: this.cookieHeaders })
      .pipe(
        retry({ count: 1, delay: error => error instanceof HttpErrorResponse && error.status === 401 ? timer(300) : throwError(() => error) }),
        map(response => { this.applySession(response.data); return response.data.accessToken; }),
        catchError(error => { this.clear(); return throwError(() => error); }),
        finalize(() => { this.refreshInFlight = null; }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    return this.refreshInFlight;
  }

  /** Used at bootstrap: resolves whether or not a session could be restored. */
  restoreSession(): Observable<boolean> {
    return this.refresh().pipe(map(() => true), catchError(() => of(false)));
  }

  /** Revokes the server session. The current access token stays valid until it expires (max 15 min) but is dropped here. */
  logout(): Observable<void> {
    return this.http.post<ApiResponse<null>>(`${API_BASE}/auth/logout`, null, { withCredentials: true, headers: this.cookieHeaders }).pipe(
      map(() => undefined),
      catchError(() => of(undefined)),
      tap(() => { this.clear(); void this.router.navigate(['/login']); }),
    );
  }

  /** Called when the refresh token is no longer valid. */
  sessionExpired(): void {
    const returnUrl = this.router.url;
    this.clear();
    void this.router.navigate(['/login'], { queryParams: returnUrl && returnUrl !== '/login' ? { returnUrl, expired: 1 } : { expired: 1 } });
  }

  updateUser(user: User): void { this.currentUser.set(user); }

  private applySession(data: AuthTokenResponse): User {
    this.token.set(data.accessToken);
    this.currentUser.set(data.user);
    return data.user;
  }

  private clear(): void {
    this.token.set(null);
    this.currentUser.set(null);
  }
}
