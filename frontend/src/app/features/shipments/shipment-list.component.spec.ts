import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { ShipmentSummary } from '../../core/api/shipment.api';
import { ShipmentListComponent } from './shipment-list.component';
import { detail, fail, ok } from './shipment-test-data';

const summary = (id: string, status: ShipmentSummary['status'] = 'PUBLISHED'): ShipmentSummary => ({
  id, shipmentCode: `TG261101-${id}`, status, createdAt: '2026-11-01T00:00:00Z', pickupTime: '2026-12-01T01:00:00Z', pickupAddress: 'Kho A', deliveryAddress: 'Cửa hàng B',
  deliveryStopCount: 1, totalQuantity: 10, totalWeightKg: 200, totalVolumeM3: 0.6, vehicleTypeCode: null, vehicleTypeName: null,
  actions: { editable: status === 'DRAFT', publishable: status === 'DRAFT', cancellable: status !== 'CANCELLED', deletable: status === 'DRAFT', attachmentsEditable: status === 'DRAFT' },
});
const page = (items: ShipmentSummary[], p = 0, total = items.length) => ok({ items, page: p, size: 10, totalElements: total, totalPages: Math.ceil(total / 10) });

describe('ShipmentListComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ShipmentListComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render() {
    const fixture = TestBed.createComponent(ShipmentListComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, list: fixture.componentInstance as ShipmentListComponent & Record<string, any> };
  }

  it('loads the first page newest first and paginates', () => {
    const { fixture, el } = render();
    const first = http.expectOne(r => r.url === '/api/v1/shipments/my');
    expect(first.request.params.get('page')).toBe('0');
    expect(first.request.params.get('sortBy')).toBe('createdAt');
    expect(first.request.params.get('sortDirection')).toBe('desc');
    first.flush(page([summary('A'), summary('B', 'DRAFT')], 0, 12));
    fixture.detectChanges();
    expect(el.textContent).toContain('TG261101-A');
    expect(el.textContent).toContain('Trang 1 / 2');
    [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Sau'))!.click();
    expect(http.expectOne(r => r.url === '/api/v1/shipments/my').request.params.get('page')).toBe('1');
  });

  it('sends filters and shows the empty state for no matches', () => {
    const { fixture, list, el } = render();
    http.expectOne(r => r.url === '/api/v1/shipments/my').flush(page([summary('A')]));
    list['filters'].patchValue({ keyword: 'TG26', status: 'DRAFT', fromDate: '2026-11-01', toDate: '2026-11-30' });
    list.search();
    const r = http.expectOne(req => req.url === '/api/v1/shipments/my');
    expect(r.request.params.get('keyword')).toBe('TG26');
    expect(r.request.params.get('status')).toBe('DRAFT');
    expect(r.request.params.get('fromDate')).toBe('2026-11-01');
    r.flush(page([]));
    fixture.detectChanges();
    expect(el.textContent).toContain('Không có đơn nào phù hợp bộ lọc');
  });

  it('shows the API error with a retry', () => {
    const { fixture, el } = render();
    http.expectOne(r => r.url === '/api/v1/shipments/my').flush(fail('Đã xảy ra lỗi hệ thống'), { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Đã xảy ra lỗi hệ thống');
    [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Thử lại'))!.click();
    http.expectOne(r => r.url === '/api/v1/shipments/my');
  });

  it('cancels an order through the confirmation dialog', () => {
    const { fixture, el } = render();
    http.expectOne(r => r.url === '/api/v1/shipments/my').flush(page([summary('A')]));
    fixture.detectChanges();
    [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Hủy')!.click();
    fixture.detectChanges();
    const confirm = [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Xác nhận hủy'))!;
    confirm.click();
    http.expectNone('/api/v1/shipments/A/cancel');
    const textarea = el.querySelector('#cancel-reason') as HTMLTextAreaElement;
    textarea.value = 'Đổi kế hoạch vận chuyển';
    textarea.dispatchEvent(new Event('input'));
    confirm.click();
    const cancel = http.expectOne('/api/v1/shipments/A/cancel');
    expect(cancel.request.body).toEqual({ reason: 'Đổi kế hoạch vận chuyển' });
    cancel.flush(ok(detail({ status: 'CANCELLED' })));
    http.expectOne(r => r.url === '/api/v1/shipments/my').flush(page([summary('A', 'CANCELLED')]));
    fixture.detectChanges();
    expect(el.textContent).toContain('Đã hủy đơn TG261101-A');
  });
});
