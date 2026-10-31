import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { DriverMarketplaceComponent } from './driver-marketplace.component';
import { fail, listItem, ok } from './marketplace-test-data';

const page = <T>(items: T[]) => ({ items, page: 0, size: 12, totalElements: items.length, totalPages: items.length ? 1 : 0 });

describe('DriverMarketplaceComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [DriverMarketplaceComponent], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render() {
    const fixture = TestBed.createComponent(DriverMarketplaceComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/catalogs/vehicle-types').flush(ok([{ code: 'TRUCK_1T', name: 'Xe tải 1 tấn' }]));
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el };
  }

  it('lists open orders with area, price and the driver’s own application state', () => {
    const { fixture, el } = render();
    const req = http.expectOne(r => r.url === '/api/v1/driver/marketplace/shipments');
    expect(req.request.params.get('sortBy')).toBe('pickupTime');
    expect(req.request.params.has('keyword')).toBe(false);   // empty filters are not sent
    req.flush(ok(page([listItem(), listItem({ id: 'sh-2', shipmentCode: 'TG261101-ZZZZZZ' }, 'PENDING')])));
    fixture.detectChanges();
    const text = el.textContent!;
    expect(el.querySelectorAll('[data-testid=marketplace-item]').length).toBe(2);
    expect(text).toContain('Cầu Giấy, Hà Nội');
    expect(text).toContain('360.000 VNĐ');
    expect(text).toContain('120 km');
    expect(text).toContain('Chờ khách chọn');
  });

  it('sends the filters the driver set', () => {
    const { fixture, el } = render();
    http.expectOne(r => r.url === '/api/v1/driver/marketplace/shipments').flush(ok(page([])));
    fixture.detectChanges();
    const input = (id: string, value: string) => {
      const e = el.querySelector<HTMLInputElement | HTMLSelectElement>('#' + id)!;
      e.value = value;
      e.dispatchEvent(new Event(e.tagName === 'SELECT' ? 'change' : 'input'));
    };
    input('mk-pp', 'ha noi');
    input('mk-maxp', '500000');
    input('mk-sort', 'price:desc');
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne(r => r.url === '/api/v1/driver/marketplace/shipments');
    expect(req.request.params.get('pickupProvince')).toBe('ha noi');
    expect(req.request.params.get('maxPrice')).toBe('500000');
    expect(req.request.params.get('sortBy')).toBe('price');
    expect(req.request.params.get('sortDirection')).toBe('desc');
    req.flush(ok(page([])));
    fixture.detectChanges();
    expect(el.textContent).toContain('Không có đơn nào phù hợp bộ lọc');
  });

  it('shows an empty state and a retryable error', () => {
    const { fixture, el } = render();
    http.expectOne(r => r.url === '/api/v1/driver/marketplace/shipments').flush(ok(page([])));
    fixture.detectChanges();
    expect(el.textContent).toContain('Hiện chưa có đơn nào đang tìm tài xế');
    [...el.querySelectorAll('button')].find(b => b.textContent?.includes('Xóa lọc'))!.click();
    http.expectOne(r => r.url === '/api/v1/driver/marketplace/shipments').flush(fail('Máy chủ bận'), { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Máy chủ bận');
  });
});
