import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AdminDriversComponent } from './admin-drivers.component';
import { envelope, failure } from '../../core/auth/test-helpers';
import { AdminDriver } from '../../core/api/account.api';

const pending: AdminDriver = {
  fullName: 'Nguyen Van B', email: 'b@example.com', phone: null,
  profile: { id: 'p-1', userId: 'u-2', licenseNumber: '012345678901', licenseClass: 'C', verificationStatus: 'PENDING_REVIEW', rejectionReason: null,
    submittedAt: '2026-10-30T00:00:00Z', reviewedAt: null, createdAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:00:00Z' },
};

describe('AdminDriversComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [AdminDriversComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] }));

  function render() {
    const fixture = TestBed.createComponent(AdminDriversComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(r => r.url === '/api/v1/admin/drivers/pending').flush(envelope({ items: [pending], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const click = (label: string) => { [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === label)!.click(); fixture.detectChanges(); };
    return { fixture, http, el, click };
  }

  it('asks for confirmation before approving', () => {
    const { http, el, click } = render();
    click('Duyệt');
    http.expectNone('/api/v1/admin/drivers/p-1/approve');
    expect(el.textContent).toContain('Xác nhận duyệt');
    click('Xác nhận duyệt');
    http.expectOne('/api/v1/admin/drivers/p-1/approve').flush(envelope({ ...pending, profile: { ...pending.profile, verificationStatus: 'APPROVED' } }));
    http.expectOne(r => r.url === '/api/v1/admin/drivers/pending').flush(envelope({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
  });

  it('keeps the row and shows the error when the backend denies the action', () => {
    const { http, el, click, fixture } = render();
    click('Duyệt');
    click('Xác nhận duyệt');
    http.expectOne('/api/v1/admin/drivers/p-1/approve').flush(failure('Bạn không có quyền thực hiện thao tác này'), { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Bạn không có quyền');
    expect(el.textContent).toContain('Nguyen Van B');
    http.expectNone(r => r.url === '/api/v1/admin/drivers/pending');
  });

  it('requires a reason to reject', () => {
    const { http, el, click } = render();
    click('Từ chối');
    click('Xác nhận từ chối');
    expect(el.textContent).toContain('Lý do dài 5–500 ký tự');
    http.expectNone('/api/v1/admin/drivers/p-1/reject');
  });
});
