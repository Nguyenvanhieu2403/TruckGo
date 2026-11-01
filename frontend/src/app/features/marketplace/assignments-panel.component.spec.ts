import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { AssignmentsPanelComponent } from './assignments-panel.component';
import { applicant, fail, ok, overview } from './marketplace-test-data';
import { AssignmentOverview } from '../../core/api/marketplace.api';

describe('AssignmentsPanelComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AssignmentsPanelComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(data: AssignmentOverview, admin = false, applicants = [applicant()]) {
    const fixture = TestBed.createComponent(AssignmentsPanelComponent);
    fixture.componentRef.setInput('shipmentId', 'sh-1');
    fixture.componentRef.setInput('admin', admin);
    fixture.detectChanges();
    http.expectOne(admin ? '/api/v1/admin/marketplace/shipments/sh-1/assignments' : '/api/v1/customer/shipments/sh-1/assignments').flush(ok(data));
    if (!admin) http.expectOne('/api/v1/customer/shipments/sh-1/applications').flush(ok(applicants));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === label);
    return { fixture, el, button };
  }

  it('shows how many vehicles are assigned and missing, with each driver', () => {
    const { el } = render(overview());
    expect(el.querySelector('[data-testid=assignment-progress]')?.textContent).toContain('1/2 xe đã có tài xế');
    expect(el.textContent).toContain('Còn thiếu 1 xe');
    expect(el.querySelector('[data-testid=assignment]')?.textContent).toContain('Lê Văn Chính');
    expect(el.textContent).toContain('29C-111.11');
  });

  it('requires a reason to remove a driver and shows the result', () => {
    const { el, button, fixture } = render(overview());
    button('Gỡ tài xế')!.click();
    fixture.detectChanges();
    [...el.querySelectorAll<HTMLButtonElement>('.dialog button')].find(b => b.textContent?.trim() === 'Gỡ tài xế')!.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    http.expectNone('/api/v1/customer/shipments/sh-1/assignments/as-1/release');
    expect(el.textContent).toContain('Vui lòng nhập lý do');
    const textarea = el.querySelector('#change-reason') as HTMLTextAreaElement;
    textarea.value = 'Tài xế báo bận';
    textarea.dispatchEvent(new Event('input'));
    [...el.querySelectorAll<HTMLButtonElement>('.dialog button')].find(b => b.textContent?.trim() === 'Gỡ tài xế')!.click();
    const req = http.expectOne('/api/v1/customer/shipments/sh-1/assignments/as-1/release');
    expect(req.request.body).toEqual({ reason: 'Tài xế báo bận' });
    req.flush(ok(overview({ assignedCount: 0, remaining: 2, assignments: [] })));
    http.expectOne('/api/v1/customer/shipments/sh-1/assignments').flush(ok(overview({ assignedCount: 0, remaining: 2, assignments: [] })));
    http.expectOne('/api/v1/customer/shipments/sh-1/applications').flush(ok([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('0/2 xe đã có tài xế');
    expect(el.querySelector('.dialog')).toBeNull();
  });

  it('replaces a driver with a pending applicant and explains a trip that already started', () => {
    const { el, button, fixture } = render(overview());
    button('Thay tài xế')!.click();
    fixture.detectChanges();
    const select = el.querySelector('#replacement') as HTMLSelectElement;
    expect(select.textContent).toContain('Trần Văn Tài');
    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    const textarea = el.querySelector('#change-reason') as HTMLTextAreaElement;
    textarea.value = 'Xe hỏng';
    textarea.dispatchEvent(new Event('input'));
    [...el.querySelectorAll<HTMLButtonElement>('.dialog button')].find(b => b.textContent?.trim() === 'Thay tài xế')!.click();
    const req = http.expectOne('/api/v1/customer/shipments/sh-1/assignments/as-1/replace');
    expect(req.request.body).toEqual({ applicationId: 'app-1', reason: 'Xe hỏng' });
    req.flush(fail('Chuyến đã bắt đầu', { code: 'TRIP_ALREADY_STARTED' }), { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/v1/customer/shipments/sh-1/assignments').flush(ok(overview({ canChange: false, changeBlockedReason: 'Chuyến đã bắt đầu; xử lý theo quy trình sự cố' })));
    http.expectOne('/api/v1/customer/shipments/sh-1/applications').flush(ok([]));
    fixture.detectChanges();
    expect(el.querySelector('.dialog [role=alert]')?.textContent).toContain('Chuyến đã bắt đầu');
  });

  it('labels system actions in the history and offers no replacement to admins', () => {
    const data = overview();
    data.history = [...data.history, { ...data.history[0], id: 'h-2', action: 'UNASSIGNED', previousDriverName: 'Lê Văn Chính', reason: 'Đơn hết hạn khi chưa đủ tài xế',
      actorType: 'SYSTEM', systemActor: 'marketplace-expiry-job', performedBy: null, performedByName: null }];
    const { el, button } = render(data, true);
    const rows = el.querySelectorAll('[data-testid=assignment-history]');
    expect(rows.length).toBe(2);
    expect(rows[1].textContent).toContain('Hệ thống (marketplace-expiry-job)');
    expect(button('Thay tài xế')).toBeUndefined();
    expect(button('Gỡ tài xế')).toBeDefined();
  });
});
