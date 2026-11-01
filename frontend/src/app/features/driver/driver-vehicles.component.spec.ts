import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DriverVehiclesComponent } from './driver-vehicles.component';
import { Vehicle } from '../../core/api/account.api';
import { ok } from '../shipments/shipment-test-data';

const BODIES = [{ code: 'ANY', name: 'Không yêu cầu' }, { code: 'OPEN', name: 'Thùng lửng' }, { code: 'TARPAULIN', name: 'Thùng mui bạt' },
  { code: 'CLOSED_BOX', name: 'Thùng kín' }, { code: 'REFRIGERATED', name: 'Thùng đông lạnh' }, { code: 'CONTAINER', name: 'Container' }];

const vehicle = (overrides: Partial<Vehicle> = {}): Vehicle => ({
  id: 'v-1', driverId: 'd-1', plateNumber: '51C12345', vehicleType: 'SMALL_TRUCK', bodyTypeCode: 'TARPAULIN', bodyTypeName: 'Thùng mui bạt', maxPayloadKg: 1500,
  lengthCm: 420, widthCm: 180, heightCm: 190, registrationExpiryDate: '2027-11-01', verificationStatus: 'APPROVED', rejectionReason: null,
  submittedAt: null, reviewedAt: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...overrides,
});

describe('DriverVehiclesComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [DriverVehiclesComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(list: Vehicle[]) {
    const fixture = TestBed.createComponent(DriverVehiclesComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/vehicles/my').flush(ok(list));
    http.expectOne('/api/v1/catalogs/vehicle-body-types').flush(ok(BODIES));
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('shows each vehicle’s body type and flags vehicles that never declared one', () => {
    const { el } = render([vehicle(), vehicle({ id: 'v-2', plateNumber: '51C99999', bodyTypeCode: null, bodyTypeName: null })]);
    expect(el.textContent).toContain('Thùng mui bạt');
    expect(el.querySelectorAll('[data-testid=body-undeclared]').length).toBe(1);
  });

  it('offers only real body types and the one a refrigerated truck must have', () => {
    const { el, fixture } = render([]);
    const body = el.querySelector('#bodyTypeCode') as HTMLSelectElement;
    const options = () => [...body.options].filter(o => !o.disabled).map(o => o.value);
    expect(options()).toEqual(['OPEN', 'TARPAULIN', 'CLOSED_BOX', 'REFRIGERATED', 'CONTAINER']);   // no "ANY"
    const type = el.querySelector('#vehicleType') as HTMLSelectElement;
    type.value = 'REFRIGERATED_TRUCK';
    type.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(options()).toEqual(['REFRIGERATED']);
  });

  it('requires a body type before saving and sends it', () => {
    const { el, fixture } = render([]);
    const set = (id: string, value: string) => {
      const input = el.querySelector('#' + id) as HTMLInputElement | HTMLSelectElement;
      input.value = value;
      input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
    };
    set('plateNumber', '51C-123.45'); set('maxPayloadKg', '1500'); set('lengthCm', '420'); set('widthCm', '180'); set('heightCm', '190');
    set('registrationExpiryDate', '2027-12-01');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    http.expectNone('/api/v1/vehicles');
    expect(el.textContent).toContain('Trường này là bắt buộc');
    set('bodyTypeCode', 'OPEN');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne(r => r.url === '/api/v1/vehicles' && r.method === 'POST');
    expect(req.request.body.bodyTypeCode).toBe('OPEN');
  });
});
