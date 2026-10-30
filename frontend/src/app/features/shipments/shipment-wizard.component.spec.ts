import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ShipmentWizardComponent } from './shipment-wizard.component';
import { BODY_TYPES, CATEGORIES, SERVICES, VEHICLE_TYPES, detail, fail, ok } from './shipment-test-data';

type Wizard = ShipmentWizardComponent & Record<string, any>;

describe('ShipmentWizardComponent', () => {
  let http: HttpTestingController;

  function create(id: string | null = null) {
    TestBed.configureTestingModule({
      imports: [ShipmentWizardComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(id ? { id } : {}), queryParamMap: convertToParamMap({}) } } }],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShipmentWizardComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/catalogs/item-categories').flush(ok(CATEGORIES));
    http.expectOne('/api/v1/catalogs/vehicle-types').flush(ok(VEHICLE_TYPES));
    http.expectOne('/api/v1/catalogs/vehicle-body-types').flush(ok(BODY_TYPES));
    http.expectOne('/api/v1/catalogs/shipment-services').flush(ok(SERVICES));
    fixture.detectChanges();
    return { fixture, wizard: fixture.componentInstance as Wizard, el: fixture.nativeElement as HTMLElement };
  }

  function fillValid(wizard: Wizard): void {
    const f = wizard['form'];
    f.controls.schedule.patchValue({ pickupTime: '2026-12-01T08:00' });
    f.controls.stops.at(0).patchValue({ contactName: 'Kho A', contactPhone: '0912345678', province: 'Hà Nội', addressDetail: 'Số 1' });
    f.controls.stops.at(1).patchValue({ contactName: 'Cửa hàng B', contactPhone: '0987654321', province: 'Hà Nội', addressDetail: 'Số 2' });
    f.controls.items.at(0).patchValue({ itemName: 'Thùng', category: 'ELECTRONICS', quantity: 10, weightPerUnitKg: 20, lengthCm: 50, widthCm: 40, heightCm: 30 });
    f.markAsDirty();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('blocks the next step until the current one is valid and keeps data between steps', () => {
    const { wizard, fixture } = create();
    wizard.next();
    expect(wizard['step']()).toBe(0);
    expect(wizard['error']()).toContain('kiểm tra');
    fillValid(wizard);
    wizard.next();
    expect(wizard['step']()).toBe(1);
    wizard.back();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('#contactName0') as HTMLInputElement).value).toBe('Kho A');
  });

  it('adds and removes deliveries and items; totals follow the form', () => {
    const { wizard, fixture, el } = create();
    fillValid(wizard);
    const click = (label: string) => { [...el.querySelectorAll('button')].filter(b => b.textContent?.trim().startsWith(label)).at(-1)!.click(); fixture.detectChanges(); };
    click('+ Thêm điểm giao');
    expect(wizard['form'].controls.stops.length).toBe(3);
    click('Xóa điểm'); // the delivery just added
    expect(wizard['form'].controls.stops.length).toBe(2);
    expect(wizard['form'].controls.stops.at(1).controls.contactName.value).toBe('Cửa hàng B');
    wizard.next();
    fixture.detectChanges();
    click('+ Thêm hàng hóa');
    wizard['form'].controls.items.at(1).patchValue({ itemName: 'Bàn', category: 'FURNITURE', quantity: 2, weightPerUnitKg: 35, lengthCm: 120, widthCm: 60, heightCm: 75 });
    fixture.detectChanges();
    expect(el.textContent).toContain('270 kg');
    expect(el.textContent).toContain('1,68 m³');
    [...el.querySelectorAll('button')].filter(b => b.textContent?.trim() === 'Xóa')[1].click();
    fixture.detectChanges();
    expect(wizard['form'].controls.items.length).toBe(1);
    expect(el.textContent).toContain('200 kg');
  });

  it('warns when the chosen vehicle is too small', () => {
    const { wizard, fixture, el } = create();
    fillValid(wizard);
    wizard['form'].controls.items.at(0).patchValue({ quantity: 30 });
    wizard.next(); wizard.next();
    wizard['form'].controls.vehicle.patchValue({ vehicleTypeCode: 'TRUCK_500KG' });
    fixture.detectChanges();
    expect(el.textContent).toContain('vượt tải trọng danh nghĩa');
  });

  it('saves a new draft once, even on double click', () => {
    const { wizard } = create();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fillValid(wizard);
    wizard.saveDraft(false);
    wizard.saveDraft(false);
    const request = http.expectOne('/api/v1/shipments/draft');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.items[0]).toMatchObject({ itemName: 'Thùng', quantity: 10, weightPerUnitKg: 20 });
    expect(request.request.body.totalWeightKg).toBeUndefined();
    request.flush(ok(detail()));
    expect(wizard.canLeave()).toBe(true);
  });

  it('publishes by saving the draft then calling publish', () => {
    const { wizard } = create();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fillValid(wizard);
    wizard.publish();
    http.expectOne('/api/v1/shipments/draft').flush(ok(detail()));
    http.expectOne('/api/v1/shipments/sh-1/publish').flush(ok(detail({ status: 'PUBLISHED' })));
    expect(navigate).toHaveBeenCalledWith(['/customer/shipments', 'sh-1'], { queryParams: { published: 1 } });
  });

  it('shows server field errors on the right step', () => {
    const { wizard, fixture } = create();
    fillValid(wizard);
    wizard.next(); wizard.next(); wizard.next(); wizard.next();
    expect(wizard['step']()).toBe(4);
    wizard.publish();
    http.expectOne('/api/v1/shipments/draft').flush(fail('Dữ liệu đơn hàng không hợp lệ', { 'stops[1].contactPhone': 'Số điện thoại di động không hợp lệ' }),
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(wizard['step']()).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Số điện thoại di động không hợp lệ');
  });

  it('edits an existing draft with its version', () => {
    const { wizard } = create('sh-1');
    http.expectOne('/api/v1/shipments/sh-1').flush(ok(detail()));
    expect(wizard['form'].controls.items.at(0).controls.itemName.value).toBe('Thùng');
    wizard['form'].controls.customerNote.setValue('Gọi trước');
    wizard['form'].markAsDirty();
    expect(wizard.canLeave()).toBe(false);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    wizard.saveDraft(false);
    const request = http.expectOne('/api/v1/shipments/sh-1/draft');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.version).toBe(4);
    expect(request.request.body.customerNote).toBe('Gọi trước');
    request.flush(ok(detail({ version: 5 })));
  });
});
