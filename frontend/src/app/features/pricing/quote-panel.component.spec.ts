import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
  afterEach(() => vi.useRealTimers());

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

  it('separates order-level fees from per-stop fees and names the stops', () => {
    const { el } = render(quote({ lines: [
      { lineNo: 1, component: 'BASE', code: 'BASE', name: 'Giá mở cửa', description: null, quantity: 1, unitPrice: 100000, amount: 100000, appliesTo: 'ORDER', stopSequences: null },
      { lineNo: 2, component: 'SERVICE', code: 'STOP_SERVICE:UNLOADING_SUPPORT:true', name: 'Hỗ trợ dỡ hàng', description: 'Tại điểm 2, 3', quantity: 2,
        unitPrice: 90000, amount: 180000, appliesTo: 'STOP', stopSequences: [2, 3] }] }));
    const stops = el.querySelector('[data-testid=stop-lines]')!;
    expect(el.textContent).toContain('Phí theo đơn');
    expect(el.textContent).toContain('Phí theo điểm dừng');
    expect(stops.textContent).toContain('Hỗ trợ dỡ hàng');
    expect(stops.textContent).toContain('Điểm 2, 3');
    expect(stops.textContent).toContain('180.000 VNĐ');
    expect(stops.textContent).not.toContain('Giá mở cửa');
  });

  it('re-reads the quote when it expires and then shows it as expired', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-01T23:59:00Z'));
    const { el, fixture } = render(quote({ expiresAt: '2026-11-02T00:00:00Z' }));
    http.expectNone('/api/v1/shipments/sh-1/pricing');
    vi.advanceTimersByTime(61_000);
    http.expectOne('/api/v1/shipments/sh-1/pricing').flush(ok(quote({ status: 'EXPIRED', actions: { acceptable: false, rejectable: false, recalculable: true } })));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=quote-expired]')?.textContent).toContain('Báo giá đã hết hạn');
  });

  it('shows the rate-limit message and blocks re-pricing until Retry-After passes', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-01T10:00:00Z'));
    const { el, click, fixture } = render(quote());
    click('Tính lại giá');
    http.expectOne('/api/v1/shipments/sh-1/pricing/preview').flush(fail('Bạn đã tính giá đơn này 5 lần trong 10 phút; vui lòng thử lại sau'),
      { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '3' } });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('vui lòng thử lại sau');
    const btn = [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Thử lại sau'))!;
    expect(btn.textContent).toContain('Thử lại sau 3 giây');
    expect(btn.disabled).toBe(true);
    vi.advanceTimersByTime(3_000);
    fixture.detectChanges();
    expect(btn.textContent?.trim()).toBe('Tính lại giá');
    expect(btn.disabled).toBe(false);
  });
});
