import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { DriverTripsComponent } from './driver-trips.component';
import { ok } from './trip-test-data';
import { TripSummary } from '../../core/api/trip.api';

const trip: TripSummary = { id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'IN_TRANSIT', pickupTime: '2026-11-02T01:00:00Z', deliveryDeadline: null,
  pickupAddress: 'Số 1, Hà Nội', deliveryAddress: 'Số 2, Hải Phòng', stopCount: 2, totalQuantity: 10, totalWeightKg: 600, price: 360000,
  vehicle: { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500 }, openIncidents: 1 };
const page = (items: TripSummary[]) => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 });

describe('DriverTripsComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [DriverTripsComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  it('lists active trips and switches tabs', () => {
    const fixture = TestBed.createComponent(DriverTripsComponent);
    fixture.detectChanges();
    const req = http.expectOne(r => r.url === '/api/v1/driver/trips');
    expect(req.request.params.get('view')).toBe('ACTIVE');
    req.flush(ok(page([trip])));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[data-testid=trip-item]').length).toBe(1);
    expect(el.textContent).toContain('360.000 VNĐ');
    expect(el.textContent).toContain('1 sự cố đang mở');
    [...el.querySelectorAll('[role=tab]')].find(b => b.textContent?.includes('Đã hoàn thành'))!.dispatchEvent(new Event('click'));
    const next = http.expectOne(r => r.url === '/api/v1/driver/trips');
    expect(next.request.params.get('view')).toBe('COMPLETED');
    next.flush(ok(page([])));
    fixture.detectChanges();
    expect(el.textContent).toContain('Chưa có chuyến nào hoàn thành');
  });
});
