import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { QuotePanelComponent } from './quote-panel.component';
import { fail, ok, quote } from './pricing-test-data';
import { Quote } from '../../core/api/pricing.api';
import { ToastService } from '../../shared/toast.service';

describe('QuotePanelComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [QuotePanelComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(current: Quote | null, owner = true) {
    const fixture = TestBed.createComponent(QuotePanelComponent);
    fixture.componentRef.setInput('shipmentId', 'sh-1');
    fixture.componentRef.setInput('owner', owner);
    fixture.componentRef.setInput('shipmentOpen', true);
    fixture.detectChanges();
    http.expectOne('/api/v1/shipments/sh-1/pricing').flush(ok(current));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === label);
    const click = (label: string) => { button(label)!.click(); fixture.detectChanges(); };
    return { fixture, el, button, click };
  }

  it('shows the breakdown, total, expiry and status from the API', () => {
    const { el } = render(quote());
    const text = el.textContent!;
    expect(text).toContain('Đã báo giá');
    for (const s of ['Giá cơ bản', '100.000 VNĐ', 'Phí vận chuyển', '160.000 VNĐ', 'Phụ phí khối lượng', '50.000 VNĐ', 'Phụ phí dịch vụ', '30.000 VNĐ',
      'Phụ phí điểm dừng', '20.000 VNĐ', 'Tổng giá', '360.000 VNĐ', 'Báo giá có hiệu lực đến', '20 km']) expect(text).toContain(s);
    expect(text).not.toContain('Phụ phí thể tích'); // zero components are hidden
  });

  it('explains a quote waiting for review and shows no price', () => {
    const { el, button } = render(quote({ status: 'AWAITING_MANUAL_REVIEW', breakdown: null, totalPrice: null, distanceKm: null, lines: [],
      reviewReason: 'Chưa cấu hình dịch vụ bản đồ', actions: { acceptable: false, rejectable: false, recalculable: false } }));
    expect(el.textContent).toContain('Chờ quản trị viên xử lý');
    expect(el.textContent).toContain('Chưa cấu hình dịch vụ bản đồ');
    expect(el.textContent).not.toMatch(/\d\s?VNĐ/);
    expect(button('Xác nhận báo giá')).toBeUndefined();
  });

  it('accepts after confirmation', () => {
    const { el, click, fixture } = render(quote());
    click('Xác nhận báo giá');
    http.expectNone('/api/v1/shipments/sh-1/pricing/accept');
    expect(el.textContent).toContain('Sau khi xác nhận, báo giá không thể thay đổi');
    click('Xác nhận');
    http.expectOne('/api/v1/shipments/sh-1/pricing/accept').flush(ok(quote({ status: 'ACCEPTED', acceptedAt: '2026-11-01T01:00:00Z',
      actions: { acceptable: false, rejectable: false, recalculable: false } })));
    fixture.detectChanges();
    expect(el.textContent).toContain('Đã xác nhận');
    expect(TestBed.inject(ToastService).toasts().map(t => t.text)).toContain('Đã xác nhận báo giá.');
  });

  it('rejects with an optional reason', () => {
    const { el, click, fixture } = render(quote());
    click('Từ chối báo giá');
    const textarea = el.querySelector('#reject-reason') as HTMLTextAreaElement;
    textarea.value = 'Giá cao';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    click('Từ chối báo giá');
    const req = http.expectOne('/api/v1/shipments/sh-1/pricing/reject');
    expect(req.request.body).toEqual({ reason: 'Giá cao' });
    req.flush(ok(quote({ status: 'REJECTED', rejectionReason: 'Giá cao', actions: { acceptable: false, rejectable: false, recalculable: true } })));
    fixture.detectChanges();
    expect(el.textContent).toContain('Bạn đã từ chối báo giá: Giá cao');
    expect(el.textContent).toContain('Tính lại giá');
  });

  it('shows an expired-quote error from the API and reloads the state', () => {
    const { el, click, fixture } = render(quote());
    click('Xác nhận báo giá');
    click('Xác nhận');
    http.expectOne('/api/v1/shipments/sh-1/pricing/accept').flush(fail('Báo giá đã hết hạn, vui lòng tính lại giá'), { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/v1/shipments/sh-1/pricing').flush(ok(quote({ status: 'EXPIRED', actions: { acceptable: false, rejectable: false, recalculable: true } })));
    fixture.detectChanges();
    expect(el.textContent).toContain('Báo giá đã hết hạn');
    expect(el.textContent).toContain('Hết hạn');
  });

  it('requests a quote when there is none and shows field errors', () => {
    const { el, click, fixture } = render(null);
    expect(el.textContent).toContain('Đơn chưa có báo giá');
    click('Tính giá');
    http.expectOne('/api/v1/shipments/sh-1/pricing/preview').flush(fail('Đơn hàng chưa đủ thông tin để báo giá', { items: 'Bổ sung kích thước' }),
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Bổ sung kích thước');
  });

  it('never shows a price when loading fails, and admins cannot act', () => {
    const fixture = TestBed.createComponent(QuotePanelComponent);
    fixture.componentRef.setInput('shipmentId', 'sh-1');
    fixture.detectChanges();
    http.expectOne('/api/v1/shipments/sh-1/pricing').flush(fail('Đã xảy ra lỗi hệ thống'), { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Không tải được báo giá');
    expect(el.textContent).not.toMatch(/\d\s?VNĐ/);
    const admin = render(quote(), false);
    expect(admin.button('Xác nhận báo giá')).toBeUndefined();
    expect(admin.button('Tính lại giá')).toBeUndefined();
  });
});
