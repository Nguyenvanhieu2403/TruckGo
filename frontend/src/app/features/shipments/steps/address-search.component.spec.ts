import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { AddressSearchComponent } from './address-search.component';
import { stopForm } from '../shipment-form';
import { fail, ok } from '../shipment-test-data';

const candidate = { formattedAddress: '1 Trần Thái Tông, Cầu Giấy, Hà Nội', latitude: 21.028512, longitude: 105.7895, precision: 'EXACT', partialMatch: false, placeId: 'p1' };

describe('AddressSearchComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AddressSearchComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(enabled: boolean) {
    const stop = stopForm('PICKUP');
    stop.patchValue({ addressDetail: '1 Trần Thái Tông', district: 'Cầu Giấy', province: 'Hà Nội' });
    const fixture = TestBed.createComponent(AddressSearchComponent);
    fixture.componentRef.setInput('stop', stop);
    fixture.detectChanges();
    http.expectOne('/api/v1/geo/status').flush(ok({ provider: enabled ? 'google' : 'none', enabled }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => [...el.querySelectorAll('button')].find(b => b.textContent?.trim() === label) as HTMLButtonElement;
    return { fixture, el, stop, button };
  }

  it('says manual entry continues when no provider is configured', () => {
    const { el } = render(false);
    expect(el.querySelector('[data-testid=geo-disabled]')?.textContent).toContain('nhập địa chỉ thủ công');
    expect(el.querySelector('input')).toBeNull();
  });

  it('searches the typed address and fills only the coordinates', () => {
    const { fixture, el, stop, button } = render(true);
    button('Dùng địa chỉ đã nhập').click();
    const req = http.expectOne(r => r.url === '/api/v1/geo/search');
    expect(req.request.params.get('q')).toBe('1 Trần Thái Tông, Cầu Giấy, Hà Nội');
    req.flush(ok([candidate]));
    fixture.detectChanges();
    expect(el.textContent).toContain('Chính xác');
    (el.querySelector('[data-testid=geo-result]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(stop.getRawValue().latitude).toBe(21.028512);
    expect(stop.getRawValue().addressDetail).toBe('1 Trần Thái Tông');   // typed fields untouched
    expect(el.querySelector('[data-testid=geo-chosen]')).not.toBeNull();
  });

  it('asks before replacing coordinates the customer entered', () => {
    const { fixture, el, stop, button } = render(true);
    stop.patchValue({ latitude: 21.1, longitude: 105.2 });
    button('Dùng địa chỉ đã nhập').click();
    http.expectOne(r => r.url === '/api/v1/geo/search').flush(ok([candidate]));
    fixture.detectChanges();
    (el.querySelector('[data-testid=geo-result]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(stop.getRawValue().latitude).toBe(21.1);
    button('Giữ tọa độ cũ').click();
    fixture.detectChanges();
    expect(stop.getRawValue().latitude).toBe(21.1);
    (el.querySelector('[data-testid=geo-result]') as HTMLButtonElement).click();
    fixture.detectChanges();
    button('Thay tọa độ').click();
    expect(stop.getRawValue().longitude).toBe(105.7895);
  });

  it('shows a provider outage without inventing results', () => {
    const { fixture, el, stop, button } = render(true);
    button('Dùng địa chỉ đã nhập').click();
    http.expectOne(r => r.url === '/api/v1/geo/search')
      .flush(fail('Không kết nối được dịch vụ bản đồ. Bạn vẫn có thể nhập địa chỉ thủ công', { code: 'GEOCODING_UNAVAILABLE' }), { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('nhập địa chỉ thủ công');
    expect(el.querySelector('[data-testid=geo-result]')).toBeNull();
    expect(stop.getRawValue().latitude).toBeNull();
  });
});
