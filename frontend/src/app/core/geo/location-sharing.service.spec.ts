import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LocationSharingService } from './location-sharing.service';
import { GEOLOCATION } from './geolocation';
import { fail, ok } from '../../features/shipments/shipment-test-data';

const POLICY = { movingIntervalSeconds: 8, idleIntervalSeconds: 30, minDistanceMeters: 25 };

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
  const ack = { accepted: true, reason: null, recordedAt: '', policy: POLICY };

  it('sends a fix in km/h, then throttles by interval and distance', () => {
    const svc = TestBed.inject(LocationSharingService);
    svc.start('sh-1', POLICY);
    expect(svc.state()).toBe('requesting');
    onPosition(fix(21.0285, 105.8542, 1_000_000));
    const first = http.expectOne('/api/v1/driver/trips/sh-1/location');
    expect(first.request.body.speed).toBe(36);
    expect(first.request.body.recordedAt).toBe(new Date(1_000_000).toISOString());
    first.flush(ok(ack));
    expect(svc.state()).toBe('sharing');

    onPosition(fix(21.0400, 105.8542, 1_003_000));   // only 3 s later
    http.expectNone('/api/v1/driver/trips/sh-1/location');
    onPosition(fix(21.02851, 105.8542, 1_010_000, 0)); // standing still, 10 s later, ~1 m away
    http.expectNone('/api/v1/driver/trips/sh-1/location');
    onPosition(fix(21.0300, 105.8542, 1_010_000));     // moved ~170 m
    http.expectOne('/api/v1/driver/trips/sh-1/location').flush(ok(ack));
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
    onPosition(fix(21.0285, 105.8542, 1_000_000));
    http.expectOne('/api/v1/driver/trips/sh-1/location')
      .flush(fail('Chỉ chia sẻ vị trí khi chuyến đang vận chuyển', { code: 'TRACKING_NOT_ACTIVE' }), { status: 409, statusText: 'Conflict' });
    expect(svc.isSharing('sh-1')).toBe(false);
    expect(cleared).toBe(true);
  });
});
