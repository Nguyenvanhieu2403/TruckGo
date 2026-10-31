import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { DriverTripIncidentsComponent } from './driver-trip-incidents.component';
import { ok } from './trip-test-data';
import { Incident } from '../../core/api/trip.api';

const incident = (o: Partial<Incident> = {}): Incident => ({
  id: 'i-1', shipmentId: 'sh-1', shipmentCode: 'TG1', type: 'TRAFFIC_JAM', description: 'Kẹt xe trên cầu', status: 'OPEN', latitude: null, longitude: null,
  occurredAt: '2026-11-01T03:00:00Z', reportedBy: 'u', reportedByName: 'Tài Xế An', resolutionNote: null, resolvedAt: null, createdAt: '2026-11-01T03:00:00Z', photos: [], ...o,
});

describe('DriverTripIncidentsComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DriverTripIncidentsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ shipmentId: 'sh-1' }) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('validates and reports an incident, then lists it', async () => {
    const fixture = TestBed.createComponent(DriverTripIncidentsComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/driver/trips/sh-1/incidents').flush(ok([]));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Chưa có sự cố nào');
    const form = el.querySelector('form')!;
    form.dispatchEvent(new Event('submit'));
    await Promise.resolve();
    http.expectNone(r => r.method === 'POST');
    const type = el.querySelector<HTMLSelectElement>('#inc-type')!;
    type.value = 'VEHICLE_BREAKDOWN';
    type.dispatchEvent(new Event('change'));
    const desc = el.querySelector<HTMLTextAreaElement>('#inc-desc')!;
    desc.value = 'Xe bị thủng lốp';
    desc.dispatchEvent(new Event('input'));
    form.dispatchEvent(new Event('submit'));
    await Promise.resolve();
    const req = http.expectOne(r => r.method === 'POST' && r.url === '/api/v1/driver/trips/sh-1/incidents');
    expect(req.request.body).toEqual({ type: 'VEHICLE_BREAKDOWN', description: 'Xe bị thủng lốp', latitude: null, longitude: null, attachments: [] });
    req.flush(ok(incident({ type: 'VEHICLE_BREAKDOWN', description: 'Xe bị thủng lốp' })));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid=incident]').length).toBe(1);
    expect(el.textContent).toContain('Hỏng xe');
  });
});
