import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiService } from './api.service';
import { requestIdInterceptor } from './request-id.interceptor';

describe('ApiService', () => {
  let service: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiService, provideHttpClient(withInterceptors([requestIdInterceptor])), provideHttpClientTesting()],
    });
    service = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('returns health response and includes a request ID', () => {
    let result: unknown;
    service.getHealth().subscribe(value => result = value);
    const request = http.expectOne('/api/v1/health');
    expect(request.request.headers.get('X-Request-ID')).toBeTruthy();
    request.flush({ success: true, message: 'ok', data: { application: 'TruckGo API', status: 'UP' }, timestamp: '2026-10-27T00:00:00Z', path: '/api/v1/health' });
    expect(result).toMatchObject({ success: true });
  });

  it('surfaces connection failures to the caller', () => {
    let failed = false;
    service.getHealth().subscribe({ error: () => failed = true });
    http.expectOne('/api/v1/health').error(new ProgressEvent('error'));
    expect(failed).toBe(true);
  });
});
