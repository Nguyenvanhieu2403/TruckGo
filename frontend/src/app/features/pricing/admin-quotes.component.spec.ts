import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AdminQuotesComponent } from './admin-quotes.component';
import { fail, ok, quote } from './pricing-test-data';

const pending = { quote: quote({ status: 'AWAITING_MANUAL_REVIEW', breakdown: null, totalPrice: null, distanceKm: null, reviewReason: 'Chưa cấu hình dịch vụ bản đồ' }),
  customerId: 'u-1', pickupAddress: 'Kho A', deliveryAddress: 'Cửa hàng B', adjustedBy: null, pricingConfigId: null, pricingSnapshot: null };

describe('AdminQuotesComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AdminQuotesComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render() {
    const fixture = TestBed.createComponent(AdminQuotesComponent);
    fixture.detectChanges();
    http.expectOne(r => r.url === '/api/v1/admin/quotes/pending').flush(ok({ items: [pending], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const click = (label: string) => { [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === label)!.click(); fixture.detectChanges(); };
    const type = (id: string, value: string) => { const i = el.querySelector(id) as HTMLInputElement; i.value = value; i.dispatchEvent(new Event('input')); fixture.detectChanges(); };
    return { fixture, el, click, type };
  }

  it('requires a reason and sends the verified distance', () => {
    const { el, click, type } = render();
    expect(el.textContent).toContain('Chưa cấu hình dịch vụ bản đồ');
    click('Xử lý');
    type('#distanceKm', '25.5');
    click('Tạo báo giá đã điều chỉnh');
    http.expectNone('/api/v1/admin/quotes/q-1/manual-adjustment');
    expect(el.textContent).toContain('Bắt buộc');
    type('#reason', 'Đã xác minh tuyến');
    click('Tạo báo giá đã điều chỉnh');
    const req = http.expectOne('/api/v1/admin/quotes/q-1/manual-adjustment');
    expect(req.request.body).toEqual({ distanceKm: 25.5, adjustmentAmount: null, reason: 'Đã xác minh tuyến' });
    req.flush(ok({ ...pending, quote: quote({ quoteVersion: 2, quoteCode: 'TG261101-ABCDEF-Q2' }) }));
    http.expectOne(r => r.url === '/api/v1/admin/quotes/pending').flush(ok({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
  });

  it('shows backend errors (e.g. no role) without changing the list', () => {
    const { el, click, type, fixture } = render();
    click('Xử lý');
    type('#reason', 'Thử điều chỉnh');
    click('Tính lại theo bảng giá hiện hành');
    http.expectOne('/api/v1/admin/quotes/q-1/recalculate').flush(fail('Bạn không có quyền thực hiện thao tác này'), { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Bạn không có quyền');
    expect(el.textContent).toContain('TG261101-ABCDEF-Q1');
  });
});
