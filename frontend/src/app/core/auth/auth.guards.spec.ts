import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { authGuard, roleGuard } from './auth.guards';
import { UserRole } from './auth.models';
import { AuthService } from './auth.service';
import { tokenEnvelope } from './test-helpers';

describe('route guards', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/admin/drivers' } as RouterStateSnapshot;

  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  function signInAs(role: UserRole): void {
    TestBed.inject(AuthService).login('x', 'y').subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/login').flush(tokenEnvelope('t', role));
  }

  const run = (guard: typeof authGuard) => TestBed.runInInjectionContext(() => guard(route, state));
  const url = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  it('sends anonymous users to login with a return URL', () => {
    expect(url(run(authGuard))).toBe('/login?returnUrl=%2Fadmin%2Fdrivers');
    expect(url(run(roleGuard('ADMIN')))).toBe('/login?returnUrl=%2Fadmin%2Fdrivers');
  });

  it('allows the matching role and redirects other roles to their own area', () => {
    signInAs('ADMIN');
    expect(run(roleGuard('ADMIN'))).toBe(true);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    signInAs('DRIVER');
    expect(url(run(roleGuard('ADMIN')))).toBe('/driver/verification');
    expect(run(authGuard)).toBe(true);
  });
});
