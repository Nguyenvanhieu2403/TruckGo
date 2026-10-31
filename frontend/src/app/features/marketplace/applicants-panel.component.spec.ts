import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ApplicantsPanelComponent } from './applicants-panel.component';
import { applicant, fail, ok } from './marketplace-test-data';
import { Applicant } from '../../core/api/marketplace.api';
import { ToastService } from '../../shared/toast.service';

const URL = '/api/v1/customer/shipments/sh-1/applications';

describe('ApplicantsPanelComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ApplicantsPanelComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(list: Applicant[], canChoose = true) {
    const fixture = TestBed.createComponent(ApplicantsPanelComponent);
    fixture.componentRef.setInput('shipmentId', 'sh-1');
    fixture.componentRef.setInput('canChoose', canChoose);
    fixture.detectChanges();
    http.expectOne(URL).flush(ok(list));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim().startsWith(label));
    const toasts = () => TestBed.inject(ToastService).toasts().map(t => t.text);
    return { fixture, el, button, toasts };
  }

  it('shows real driver data only, without a rating or avatar', () => {
    const { el } = render([applicant(), applicant({ id: 'app-2', status: 'REJECTED', responseReason: 'Cần xe lớn hơn' })]);
    expect(el.querySelectorAll('[data-testid=applicant]').length).toBe(2);
    expect(el.textContent).toContain('Trần Văn Tài');
    expect(el.textContent).toContain('3 chuyến đã hoàn thành');
    expect(el.textContent).toContain('51C-123.45');
    expect(el.textContent).not.toMatch(/sao|★|đánh giá/i);
    expect(el.querySelector('img')).toBeNull();
    expect(el.textContent).toContain('Cần xe lớn hơn');
  });

  it('chooses a driver after confirmation', () => {
    const { fixture, el, button, toasts } = render([applicant()]);
    button('Chọn tài xế này')!.click();
    fixture.detectChanges();
    http.expectNone(`${URL}/app-1/accept`);
    expect(el.querySelector('[role=dialog]')?.textContent).toContain('các yêu cầu khác sẽ tự động bị từ chối');
    button('Xác nhận chọn')!.click();
    http.expectOne(`${URL}/app-1/accept`).flush(ok(applicant({ status: 'ACCEPTED', driver: { ...applicant().driver!, phone: '0912345678' } })));
    http.expectOne(URL).flush(ok([applicant({ status: 'ACCEPTED', driver: { ...applicant().driver!, phone: '0912345678' } })]));
    fixture.detectChanges();
    expect(toasts()).toContain('Đã chọn Trần Văn Tài cho đơn.');
    expect(el.textContent).toContain('0912345678');
    expect(button('Chọn tài xế này')).toBeUndefined();
  });

  it('closes the dialog and refreshes when another tab already assigned the order', () => {
    const { fixture, el, button, toasts } = render([applicant()]);
    button('Chọn tài xế này')!.click();
    fixture.detectChanges();
    button('Xác nhận chọn')!.click();
    http.expectOne(`${URL}/app-1/accept`).flush(fail('Đơn đã có tài xế nhận', { code: 'SHIPMENT_ALREADY_ASSIGNED' }), { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[role=dialog]')).toBeNull();
    expect(toasts()).toContain('Đơn này vừa có tài xế khác nhận.');
    http.expectOne(URL).flush(ok([applicant({ status: 'REJECTED' })]));
  });

  it('rejects with an optional reason', () => {
    const { fixture, el, button } = render([applicant()]);
    button('Từ chối')!.click();
    fixture.detectChanges();
    const reason = el.querySelector<HTMLTextAreaElement>('#confirm-reason')!;
    reason.value = 'Cần xe đến sớm hơn';
    reason.dispatchEvent(new Event('input'));
    [...el.querySelectorAll('[role=dialog] button')].find(b => b.textContent?.trim() === 'Từ chối')!.dispatchEvent(new Event('click'));
    const req = http.expectOne(`${URL}/app-1/reject`);
    expect(req.request.body).toEqual({ reason: 'Cần xe đến sớm hơn' });
    req.flush(ok(applicant({ status: 'REJECTED' })));
    http.expectOne(URL).flush(ok([]));
  });

  it('hides actions once the order is no longer open and warns about unfit vehicles', () => {
    const { el, button } = render([applicant({ vehicleEligible: false, vehicleIssues: ['Đăng kiểm hết hạn trước ngày lấy hàng'] })], false);
    expect(button('Chọn tài xế này')).toBeUndefined();
    expect(el.textContent).toContain('Đăng kiểm hết hạn trước ngày lấy hàng');
  });
});
