import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { CustomerTrackingComponent } from './customer-tracking.component';
import { customerTrip, fail, ok, tracking } from './trip-test-data';
import { CustomerTrip } from '../../core/api/trip.api';
import { StreamStatus, TripEvent, TripStreamClient } from '../../core/trip-stream';

describe('CustomerTrackingComponent', () => {
  let http: HttpTestingController;
  let events: Subject<TripEvent>;
  let setStatus: (s: StreamStatus) => void;
  beforeEach(() => {
    events = new Subject<TripEvent>();
    const stream = { connect: (_: string, onStatus: (s: StreamStatus) => void) => { setStatus = onStatus; return events.asObservable(); } };
    TestBed.configureTestingModule({
      imports: [CustomerTrackingComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: TripStreamClient, useValue: stream },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ shipmentId: 'sh-1' }) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function render(trip: CustomerTrip) {
    const fixture = TestBed.createComponent(CustomerTrackingComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/customer/shipments/sh-1/trip').flush(ok(trip));
    http.expectOne('/api/v1/customer/shipments/sh-1/incidents').flush(ok([]));
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the live position from real coordinates and updates it from the stream', () => {
    const { fixture, el } = render(customerTrip());
    expect(el.querySelector('[data-testid=tracking-state]')?.textContent).toContain('Đang cập nhật');
    expect(el.textContent).toContain('21.02850, 105.85420');
    expect(el.querySelector('a[href*="openstreetmap.org"]')).not.toBeNull();
    setStatus('open');
    events.next({ type: 'location', data: tracking({ latitude: 21.04, longitude: 105.86, state: 'STALE', ageSeconds: 120 }) });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=tracking-state]')?.textContent).toContain('Vị trí cũ');
    expect(el.textContent).toContain('Vị trí này đã cũ');
    expect(el.textContent).toContain('2 phút trước');
    expect(el.querySelector('[data-testid=stream-status]')?.textContent).toContain('trực tiếp');
  });

  it('warns when the driver is offline and never invents a position', () => {
    const { el } = render(customerTrip({ tracking: tracking({ state: 'OFFLINE', latitude: null, longitude: null, recordedAt: null, ageSeconds: null, speed: null }) }));
    expect(el.textContent).toContain('Không nhận được vị trí mới từ tài xế');
    expect(el.textContent).not.toContain('Tài xế ·');
  });

  it('shows no position once the trip is completed', () => {
    const { el } = render(customerTrip({ status: 'COMPLETED', tracking: tracking({ state: 'ENDED', latitude: null, longitude: null, session: 'ENDED' }) }));
    expect(el.textContent).toContain('Chuyến đã kết thúc theo dõi vị trí');
    expect(el.textContent).not.toContain('21.0285');
  });

  it('confirms delivery with the code and reports a wrong code', () => {
    const confirmation = { pending: true, expiresAt: '2026-11-01T04:00:00Z', attemptsLeft: 5, resendAvailableAt: '2099-01-01T00:00:00Z', channel: 'in-app', locked: false };
    const { fixture, el } = render(customerTrip({ status: 'DELIVERED', confirmation, tracking: tracking({ state: 'ENDED', latitude: null, longitude: null }) }));
    const input = el.querySelector<HTMLInputElement>('#otp')!;
    const confirm = () => [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Xác nhận đã nhận hàng')) as HTMLButtonElement;
    expect(confirm().disabled).toBe(true);
    input.value = '123456';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    confirm().click();
    const req = http.expectOne('/api/v1/customer/shipments/sh-1/confirm-delivery');
    expect(req.request.body).toEqual({ otp: '123456', note: null });
    req.flush(fail('Mã xác nhận không đúng', { code: 'OTP_INVALID', attemptsLeft: 4 }), { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Mã xác nhận không đúng');
    http.expectOne('/api/v1/customer/shipments/sh-1/trip').flush(ok(customerTrip({ status: 'DELIVERED', confirmation: { ...confirmation, attemptsLeft: 4 } })));
    http.expectOne('/api/v1/customer/shipments/sh-1/incidents').flush(ok([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('Còn 4 lần nhập');
    expect([...el.querySelectorAll('button')].find(b => b.textContent?.includes('Gửi lại mã sau'))?.disabled).toBe(true);
  });
});
