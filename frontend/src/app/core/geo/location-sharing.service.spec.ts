import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LocationSharingService } from './location-sharing.service';
import { GEOLOCATION } from './geolocation';
import { fail, ok } from '../../features/shipments/shipment-test-data';

const POLICY = { movingIntervalSeconds: 8, idleIntervalSeconds: 30, minDistanceMeters: 25, maxAgeSeconds: 600 };

describe('LocationSharingService', () => {
  let http: HttpTestingController;
  let onPosition: (p: GeolocationPosition) => void;
  let onError: (e: GeolocationPositionError) => void;
  let cleared = false;

  beforeEach(() => {
    cleared = false;
    const geo = {
      watchPosition: (s: (p: GeolocationPosition) => void, e: (err: GeolocationPositionError) => void) => { onPosition = s; onError = e; return 7; },
      clearWatch: () => { cleared = true; }, getCurrentPosition: () => undefined,
    } as unknown as Geolocation;
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), { provide: GEOLOCATION, useValue: geo }] });
    http = TestBed.inject(HttpTestingController);
  });

  const fix = (lat: number, lng: number, t: number, speed: number | null = 10) =>
    ({ coords: { latitude: lat, longitude: lng, accuracy: 12, speed, heading: 90, altitude: null, altitudeAccuracy: null }, timestamp: t }) as unknown as GeolocationPosition;
  const T0 = Date.now();
  const ack = { accepted: true, reason: null, recordedAt: '', policy: POLICY };

  it('sends a fix in km/h, then throttles by interval and distance', () => {
    const svc = TestBed.inject(LocationSharingService);
    svc.start('sh-1', POLICY);
    expect(svc.state()).toBe('requesting');
    onPosition(fix(21.0285, 105.8542, T0));
    const first = http.expectOne('/api/v1/driver/trips/sh-1/location');
    expect(first.request.body.speed).toBe(36);
    expect(first.request.body.recordedAt).toBe(new Date(T0).toISOString());
    first.flush(ok(ack));
    expect(svc.state()).toBe('sharing');

    onPosition(fix(21.0400, 105.8542, T0 + 3000));   // only 3 s later
    http.expectNone('/api/v1/driver/trips/sh-1/location');
    onPosition(fix(21.02851, 105.8542, T0 + 10_000, 0)); // standing still, 10 s later, ~1 m away
    http.expectNone('/api/v1/driver/trips/sh-1/location');
    onPosition(fix(21.0300, 105.8542, T0 + 10_000));     // moved ~170 m
    http.expectOne('/api/v1/driver/trips/sh-1/location').flush(ok(ack));
  });

  it('sends the fix age so a wrong device clock does not matter, and holds back fixes the OS kept from long ago', () => {
    const svc = TestBed.inject(LocationSharingService);
    svc.start('sh-1', POLICY);
    onPosition(fix(21.0285, 105.8542, Date.now() - 20 * 60_000));   // a 20-minute-old reading (Safari on macOS does this)
    http.expectNone('/api/v1/driver/trips/sh-1/location');
    expect(svc.message()).toContain('chưa có vị trí mới');
    onPosition(fix(21.0285, 105.8542, Date.now() - 1500));
    const req = http.expectOne('/api/v1/driver/trips/sh-1/location');
    expect(req.request.body.ageMs).toBeGreaterThanOrEqual(1500);
    expect(req.request.body.ageMs).toBeLessThan(5000);
    req.flush(ok(ack));
  });

  it('reports a denied permission and stops watching', () => {
    const svc = TestBed.inject(LocationSharingService);
    svc.start('sh-1', POLICY);
    onError({ code: 1, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3, message: 'denied' } as GeolocationPositionError);
    expect(svc.state()).toBe('denied');
    expect(svc.message()).toContain('từ chối quyền vị trí');
    expect(cleared).toBe(true);
  });

  it('stops when the server no longer accepts positions for the trip', () => {
    const svc = TestBed.inject(LocationSharingService);
    svc.start('sh-1', POLICY);
    onPosition(fix(21.0285, 105.8542, T0));
    http.expectOne('/api/v1/driver/trips/sh-1/location')
      .flush(fail('Chỉ chia sẻ vị trí khi chuyến đang vận chuyển', { code: 'TRACKING_NOT_ACTIVE' }), { status: 409, statusText: 'Conflict' });
    expect(svc.isSharing('sh-1')).toBe(false);
    expect(cleared).toBe(true);
  });
});
