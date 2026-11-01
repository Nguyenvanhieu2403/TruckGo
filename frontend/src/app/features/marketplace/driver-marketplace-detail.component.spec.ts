import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DriverMarketplaceDetailComponent } from './driver-marketplace-detail.component';
import { driverApplication, fail, marketDetail, ok } from './marketplace-test-data';
import { MarketplaceShipmentDetail } from '../../core/api/marketplace.api';

describe('DriverMarketplaceDetailComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DriverMarketplaceDetailComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ shipmentId: 'sh-1' }) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function render(overrides: Partial<MarketplaceShipmentDetail> = {}) {
    const fixture = TestBed.createComponent(DriverMarketplaceDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/driver/marketplace/shipments/sh-1').flush(ok(marketDetail(overrides)));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim().startsWith(label));
    const dialogButton = (label: string) => [...el.querySelectorAll('[role=dialog] button')].find(b => b.textContent?.trim() === label) as HTMLButtonElement;
    return { fixture, el, button, dialogButton };
  }

  it('shows only the area before assignment and explains why vehicles do not fit', () => {
    const { el } = render();
    expect(el.textContent).toContain('Hà Nội');
    expect(el.textContent).toContain('Địa chỉ chi tiết và số điện thoại liên hệ sẽ hiển thị khi khách hàng chọn bạn');
    expect(el.querySelector('a[href^="tel:"]')).toBeNull();
    const radios = el.querySelectorAll<HTMLInputElement>('input[type=radio]');
    expect(radios[0].checked).toBe(true);            // the only eligible vehicle is preselected
    expect(radios[1].disabled).toBe(true);
    expect(el.textContent).toContain('Tải trọng xe nhỏ hơn tổng khối lượng hàng');
  });

  it('applies only after confirmation and opens the new application', () => {
    const { fixture, el, button, dialogButton } = render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button('Gửi yêu cầu nhận đơn')!.click();
    fixture.detectChanges();
    http.expectNone('/api/v1/driver/marketplace/shipments/sh-1/applications');
    expect(el.querySelector('[role=dialog]')?.textContent).toContain('51C-123.45');
    dialogButton('Gửi yêu cầu').click();
    const req = http.expectOne('/api/v1/driver/marketplace/shipments/sh-1/applications');
    expect(req.request.body).toEqual({ vehicleId: 'v-1', driverMessage: null });
    req.flush(ok(driverApplication({ id: 'app-9' })));
    expect(navigate).toHaveBeenCalledWith(['/driver/applications', 'app-9']);
  });

  it('tells the driver when someone else got the order and refreshes it', () => {
    const { fixture, el, button, dialogButton } = render();
    button('Gửi yêu cầu nhận đơn')!.click();
    fixture.detectChanges();
    dialogButton('Gửi yêu cầu').click();
    http.expectOne('/api/v1/driver/marketplace/shipments/sh-1/applications')
      .flush(fail('Đơn đã có tài xế nhận', { code: 'SHIPMENT_ALREADY_ASSIGNED' }), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[role=dialog] [role=alert]')?.textContent).toContain('Đơn này vừa có tài xế khác nhận.');
    http.expectOne('/api/v1/driver/marketplace/shipments/sh-1').flush(ok(marketDetail({ canApply: false, unavailableReason: 'Đơn đã có tài xế nhận' })));
    fixture.detectChanges();
    expect(el.textContent).toContain('Đơn đã có tài xế nhận');
  });

  it('points an unapproved driver to the verification page', () => {
    const { el } = render({ driverApproved: false, canApply: false, unavailableReason: 'Hồ sơ tài xế chưa được duyệt' });
    expect(el.textContent).toContain('Hồ sơ tài xế chưa được duyệt');
    expect(el.querySelector('a[href="/driver/verification"]')).not.toBeNull();
    expect(el.querySelector('form')).toBeNull();
  });

  it('explains a schedule conflict and shows the multi-vehicle progress and body types', () => {
    const { el, button } = render({
      canApply: false, unavailableReason: 'Bạn đã có chuyến khác trùng thời gian với đơn này', scheduleConflicts: ['Tài xế đã có chuyến TG261101-OTHER1 trùng thời gian'],
      vehicleCount: 2, assignedVehicleCount: 1,
    });
    expect(el.textContent).toContain('Bạn đã có chuyến khác trùng thời gian với đơn này');
    expect(el.querySelector('[data-testid=schedule-conflicts]')?.textContent).toContain('TG261101-OTHER1');
    expect(el.querySelector('[data-testid=vehicle-progress]')?.textContent).toContain('Cần 2 xe · đã có 1 · còn 1');
    expect(button('Gửi yêu cầu nhận đơn')).toBeUndefined();
  });
});
