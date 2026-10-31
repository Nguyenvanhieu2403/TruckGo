import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { DriverTripDetailComponent } from './driver-trip-detail.component';
import { driverTrip, fail, ok, stop } from './trip-test-data';
import { DriverTrip } from '../../core/api/trip.api';
import { GEOLOCATION } from '../../core/geo/geolocation';

describe('DriverTripDetailComponent', () => {
  let http: HttpTestingController;
  let geoCalls = 0;
  beforeEach(() => {
    geoCalls = 0;
    const geo = { getCurrentPosition: () => { geoCalls++; }, watchPosition: () => { geoCalls++; return 1; }, clearWatch: () => undefined } as unknown as Geolocation;
    TestBed.configureTestingModule({
      imports: [DriverTripDetailComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: GEOLOCATION, useValue: geo },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ shipmentId: 'sh-1' }) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(overrides: Partial<DriverTrip> = {}) {
    const fixture = TestBed.createComponent(DriverTripDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/driver/trips/sh-1').flush(ok(driverTrip(overrides)));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string, scope = el) => [...scope.querySelectorAll('button')].find(b => b.textContent?.trim() === label) as HTMLButtonElement | undefined;
    return { fixture, el, button };
  }

  it('shows the trip, contacts and only the next allowed action', async () => {
    const { el, button } = await render();
    expect(el.textContent).toContain('TG261101-ABCDEF');
    expect(el.querySelector('a[href="tel:0987654321"]')).not.toBeNull();
    expect(button('Đã đến điểm lấy hàng')).toBeDefined();
    expect(button('Xác nhận đã giao hàng')).toBeUndefined();
    // No location is requested and no sharing panel exists before the trip is in transit.
    expect(el.querySelector('app-gps-panel')).toBeNull();
    expect(geoCalls).toBe(0);
  });

  it('confirms a step before sending it and shows the updated trip', async () => {
    const { fixture, el, button } = await render();
    button('Đã đến điểm lấy hàng')!.click();
    fixture.detectChanges();
    http.expectNone('/api/v1/driver/trips/sh-1/arrived');
    const dialog = el.querySelector('[role=dialog]') as HTMLElement;
    button('Đã đến điểm lấy hàng', dialog)!.click();
    await Promise.resolve();
    const req = http.expectOne('/api/v1/driver/trips/sh-1/arrived');
    expect(req.request.body).toEqual({ note: null, attachments: [] });
    req.flush(ok(driverTrip({ status: 'DRIVER_ARRIVED', nextAction: 'CONFIRM_PICKUP', stops: [stop(1, 'PICKUP', 'ARRIVED', true), stop(2, 'DELIVERY')] })));
    fixture.detectChanges();
    expect(button('Xác nhận đã nhận hàng')).toBeDefined();
    expect(el.querySelector('#proof')).not.toBeNull();
  });

  it('keeps the dialog open with the server message when the step is refused', async () => {
    const { fixture, el, button } = await render({ status: 'IN_TRANSIT', nextAction: 'DELIVER', trackingAllowed: true, trackingSession: 'ACTIVE' });
    button('Xác nhận đã giao hàng')!.click();
    fixture.detectChanges();
    button('Xác nhận đã giao hàng', el.querySelector('[role=dialog]') as HTMLElement)!.click();
    await Promise.resolve();
    http.expectOne('/api/v1/driver/trips/sh-1/deliver').flush(fail('Còn điểm dừng chưa hoàn thành', { code: 'STOPS_INCOMPLETE' }), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[role=dialog] [role=alert]')?.textContent).toContain('Còn điểm dừng chưa hoàn thành');
    expect(el.querySelector('app-gps-panel')).not.toBeNull();
  });

  it('requires a delivery photo when the server says so', async () => {
    const { button } = await render({ status: 'IN_TRANSIT', nextAction: 'DELIVER', deliveryPhotoRequired: true, trackingAllowed: true });
    expect(button('Xác nhận đã giao hàng')!.disabled).toBe(true);
  });
});
