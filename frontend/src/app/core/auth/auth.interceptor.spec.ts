import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { failure, tokenEnvelope } from './test-helpers';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  function signIn(token = 'token-1'): void {
    auth.login('a@example.com', 'secret-123').subscribe();
    backend.expectOne('/api/v1/auth/login').flush(tokenEnvelope(token));
  }

  it('attaches the access token to API calls but not to auth endpoints or other hosts', () => {
    signIn();
    http.get('/api/v1/users/me').subscribe();
    expect(backend.expectOne('/api/v1/users/me').request.headers.get('Authorization')).toBe('Bearer token-1');
    http.get('https://example.com/data').subscribe();
    expect(backend.expectOne('https://example.com/data').request.headers.has('Authorization')).toBe(false);
    auth.refresh().subscribe();
    const refresh = backend.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    expect(refresh.request.withCredentials).toBe(true);
    expect(refresh.request.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    refresh.flush(tokenEnvelope('token-2'));
  });

  it('refreshes once for concurrent 401s and replays each request with the new token', () => {
    signIn();
    const results: unknown[] = [];
    http.get('/api/v1/users/me').subscribe(r => results.push(r));
    http.get('/api/v1/drivers/me').subscribe(r => results.push(r));
    backend.expectOne('/api/v1/users/me').flush(failure('expired'), { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/drivers/me').flush(failure('expired'), { status: 401, statusText: 'Unauthorized' });

    const refreshes = backend.match('/api/v1/auth/refresh');
    expect(refreshes).toHaveLength(1);
    refreshes[0].flush(tokenEnvelope('token-2'));

    const replays = [backend.expectOne('/api/v1/users/me'), backend.expectOne('/api/v1/drivers/me')];
    for (const replay of replays) {
      expect(replay.request.headers.get('Authorization')).toBe('Bearer token-2');
      replay.flush({ ok: true });
    }
    expect(results).toHaveLength(2);
  });

  it('does not refresh again when the replayed request is still 401', () => {
    signIn();
    let failed = false;
    http.get('/api/v1/users/me').subscribe({ error: () => failed = true });
    backend.expectOne('/api/v1/users/me').flush(failure('expired'), { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(tokenEnvelope('token-2'));
    backend.expectOne('/api/v1/users/me').flush(failure('still no'), { status: 401, statusText: 'Unauthorized' });
    backend.expectNone('/api/v1/auth/refresh');
    expect(failed).toBe(true);
  });

  it('clears the session and goes to /login when refresh fails, without looping', async () => {
    signIn();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    let failed = false;
    http.get('/api/v1/users/me').subscribe({ error: () => failed = true });
    backend.expectOne('/api/v1/users/me').flush(failure('expired'), { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/auth/refresh').flush(failure('invalid'), { status: 401, statusText: 'Unauthorized' });
    await new Promise(resolve => setTimeout(resolve, 350)); // one delayed retry for a cross-tab rotation race
    backend.expectOne('/api/v1/auth/refresh').flush(failure('invalid'), { status: 401, statusText: 'Unauthorized' });
    backend.expectNone('/api/v1/auth/refresh');
    backend.expectNone('/api/v1/users/me');
    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('passes 403 through without refreshing', () => {
    signIn();
    let status = 0;
    http.post('/api/v1/admin/drivers/x/approve', null).subscribe({ error: e => status = e.status });
    backend.expectOne('/api/v1/admin/drivers/x/approve').flush(failure('forbidden'), { status: 403, statusText: 'Forbidden' });
    backend.expectNone('/api/v1/auth/refresh');
    expect(status).toBe(403);
  });

  it('never stores tokens in web storage', () => {
    signIn('secret-token');
    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
    expect(stored).not.toContain('secret-token');
  });
});
