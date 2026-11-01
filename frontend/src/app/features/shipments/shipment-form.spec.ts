import { describe, expect, it } from 'vitest';
import { applyServerErrors, computeTotals, ensureServiceControls, itemForm, patchFromDetail, shipmentForm, stopForm, toPayload } from './shipment-form';
import { SERVICES, detail } from './shipment-test-data';

describe('shipment form model', () => {
  it('computes totals with the backend formulas (spec example: 330 kg, 2.562 m³)', () => {
    const totals = computeTotals([
      { quantity: 10, weightPerUnitKg: 20, lengthCm: 50, widthCm: 40, heightCm: 30 },
      { quantity: 2, weightPerUnitKg: 35, lengthCm: 120, widthCm: 60, heightCm: 75 },
      { quantity: 1, weightPerUnitKg: 60, lengthCm: 70, widthCm: 70, heightCm: 180 },
    ]);
    expect(totals).toEqual({ quantity: 13, weightKg: 330, volumeM3: 2.562, itemsWithoutSize: 0 });
  });

  it('avoids float drift and counts items without dimensions', () => {
    const totals = computeTotals([{ quantity: 3, weightPerUnitKg: 0.1, lengthCm: null, widthCm: null, heightCm: null }]);
    expect(totals.weightKg).toBe(0.3);
    expect(totals.volumeM3).toBe(0);
    expect(totals.itemsWithoutSize).toBe(1);
  });

  it('requires a pickup first and at least one delivery', () => {
    const form = shipmentForm();
    expect(form.controls.stops.valid).toBe(false); // contact fields empty, but the structure is fine
    expect(form.controls.stops.hasError('noDelivery')).toBe(false);
    form.controls.stops.removeAt(1);
    expect(form.controls.stops.hasError('noDelivery')).toBe(true);
    form.controls.stops.insert(0, stopForm('DELIVERY'));
    expect(form.controls.stops.hasError('pickupFirst')).toBe(true);
  });

  it('rejects partial dimensions and non-positive weights', () => {
    const item = itemForm();
    item.patchValue({ itemName: 'Tủ', category: 'FURNITURE', quantity: 1, weightPerUnitKg: 0, lengthCm: 10 });
    expect(item.hasError('partialDimensions')).toBe(true);
    expect(item.controls.weightPerUnitKg.hasError('min')).toBe(true);
    item.patchValue({ weightPerUnitKg: 5, widthCm: 10, heightCm: 10 });
    expect(item.valid).toBe(true);
  });

  it('builds a payload without totals, owner, status or price', () => {
    const form = shipmentForm();
    ensureServiceControls(form, SERVICES);
    form.controls.schedule.patchValue({ pickupTime: '2026-12-01T08:00' });
    form.controls.services.patchValue({ FRAGILE_GOODS: true, LOADING_SUPPORT: 'TEAM', UPSTAIRS_DELIVERY: 3, ELEVATOR_AVAILABLE: false, SPECIAL_REQUIREMENT: '  ' });
    const payload = toPayload(form, SERVICES, 7);
    expect(payload.pickupTime).toBe(new Date('2026-12-01T08:00').toISOString());
    expect(payload.services).toEqual([
      { serviceCode: 'LOADING_SUPPORT', serviceValue: 'TEAM' }, { serviceCode: 'UPSTAIRS_DELIVERY', serviceValue: '3' }, { serviceCode: 'FRAGILE_GOODS', serviceValue: 'true' },
    ]);
    expect(payload.stops.map(s => s.sequenceNo)).toEqual([1, 2]);
    expect(payload.version).toBe(7);
    for (const forbidden of ['totalWeightKg', 'totalVolumeM3', 'customerId', 'status', 'estimatedPrice']) expect(payload).not.toHaveProperty(forbidden);
  });

  it('round-trips a saved draft', () => {
    const form = shipmentForm();
    patchFromDetail(form, detail(), SERVICES);
    expect(form.controls.stops.length).toBe(2);
    expect(form.controls.items.at(0).controls.quantity.value).toBe(10);
    expect(form.controls.services.controls['LOADING_SUPPORT'].value).toBe('TEAM');
    expect(form.controls.services.controls['FRAGILE_GOODS'].value).toBe(false);
    expect(form.pristine).toBe(true);
    expect(toPayload(form, SERVICES).services).toEqual([{ serviceCode: 'LOADING_SUPPORT', serviceValue: 'TEAM' }]);
  });

  it('maps server field errors to controls and the earliest step', () => {
    const form = shipmentForm();
    ensureServiceControls(form, SERVICES);
    form.controls.services.patchValue({ UPSTAIRS_DELIVERY: 3 });
    const payload = toPayload(form, SERVICES);
    const result = applyServerErrors(form, { 'items[0].category': 'Loại hàng không hợp lệ', 'stops[1].contactPhone': 'Số sai', 'services[0].serviceValue': 'Ngoài phạm vi', other: 'X' }, payload);
    expect(result.step).toBe(0);
    expect(result.unmatched).toEqual(['X']);
    expect(form.controls.stops.at(1).controls.contactPhone.errors?.['server']).toBe('Số sai');
    expect(form.controls.items.at(0).controls.category.errors?.['server']).toBe('Loại hàng không hợp lệ');
    expect(form.controls.services.controls['UPSTAIRS_DELIVERY'].errors?.['server']).toBe('Ngoài phạm vi');
  });

  it('sends per-stop services with their stop and maps their server errors back to that stop', () => {
    const form = shipmentForm();
    ensureServiceControls(form, SERVICES);
    form.controls.stops.at(1).controls.services.setValue({ UNLOADING_SUPPORT: 'true', LOADING_SUPPORT: '' });
    const payload = toPayload(form, SERVICES);
    expect(payload.stops[0].services).toEqual([]);
    expect(payload.stops[1].services).toEqual([{ serviceCode: 'UNLOADING_SUPPORT', serviceValue: 'true' }]);
    const result = applyServerErrors(form, { 'stops[1].services[0].serviceCode': 'Dịch vụ không áp dụng cho điểm giao' }, payload);
    expect(result.step).toBe(0);
    expect(form.controls.stops.at(1).controls.services.errors?.['server']).toBe('Dịch vụ không áp dụng cho điểm giao');
  });
});
