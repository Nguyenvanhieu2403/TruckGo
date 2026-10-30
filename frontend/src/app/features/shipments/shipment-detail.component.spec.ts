import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { ShipmentDetailComponent } from './shipment-detail.component';
import { detail, fail, ok } from './shipment-test-data';

describe('ShipmentDetailComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShipmentDetailComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'sh-1' }), queryParamMap: convertToParamMap({}) } } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function render(overrides = {}) {
    const fixture = TestBed.createComponent(ShipmentDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/shipments/sh-1').flush(ok(detail(overrides)));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim().startsWith(label));
    return { fixture, el, button };
  }

  it('shows the order without a made-up price', () => {
    const { el } = render();
    expect(el.textContent).toContain('TG261101-ABCDEF');
    expect(el.textContent).toContain('Chưa tính giá – sẽ được hệ thống báo giá ở bước tiếp theo');
    expect(el.textContent).toContain('Hỗ trợ bốc hàng: Cần đội bốc xếp');
    expect(el.textContent).not.toMatch(/\d+\s?(đ|VND)/);
  });

  it('shows publish errors from the API and keeps the order unchanged', () => {
    const { fixture, el, button } = render();
    button('Đăng đơn')!.click();
    http.expectOne('/api/v1/shipments/sh-1/publish').flush(fail('Đơn hàng chưa đủ thông tin để đăng', { pickupTime: 'Thời gian lấy hàng phải sau thời điểm hiện tại' }),
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Thời gian lấy hàng phải sau thời điểm hiện tại');
    expect(el.textContent).toContain('Nháp');
  });

  it('hides actions the backend does not allow', () => {
    const { button } = render({ status: 'PUBLISHED', actions: { editable: false, publishable: false, cancellable: true, deletable: false, attachmentsEditable: false } });
    expect(button('Đăng đơn')).toBeUndefined();
    expect(button('Xóa nháp')).toBeUndefined();
    expect(button('Hủy đơn')).toBeDefined();
  });
});
