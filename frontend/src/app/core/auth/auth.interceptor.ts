import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { API_BASE } from '../api.service';
import { AuthService } from './auth.service';

const RETRIED_AFTER_REFRESH = new HttpContextToken<boolean>(() => false);
const AUTH_ENDPOINTS = `${API_BASE}/auth/`;

function withToken(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}

/**
 * Attaches the in-memory access token to API calls. On a 401 it refreshes once (shared across concurrent requests)
 * and replays the request a single time; auth endpoints and replayed requests are never refreshed again, so there is
 * no refresh loop. If refresh fails the session is cleared and the user is sent to /login.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(API_BASE)) return next(request);
  const auth = inject(AuthService);
  const isAuthEndpoint = request.url.startsWith(AUTH_ENDPOINTS);
  const outgoing = isAuthEndpoint ? request : withToken(request, auth.accessToken());

  return next(outgoing).pipe(catchError((error: unknown) => {
    const canRefresh = error instanceof HttpErrorResponse && error.status === 401 && !isAuthEndpoint
      && !request.context.get(RETRIED_AFTER_REFRESH) && auth.accessToken() !== null;
    if (!canRefresh) return throwError(() => error);
    return auth.refresh().pipe(
      catchError(() => { auth.sessionExpired(); return throwError(() => error); }),
      switchMap(token => next(withToken(request.clone({ context: request.context.set(RETRIED_AFTER_REFRESH, true) }), token))),
    );
  }));
};
