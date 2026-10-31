import { describe, expect, it } from 'vitest';
import { extraFeeForm, pricingConfigForm, timeRuleForm, toConfigPayload } from './pricing-config-form';

function valid() {
  const form = pricingConfigForm();
  form.patchValue({ name: 'Xe 1 tấn nội thành', vehicleTypeCode: 'TRUCK_1T', basePrice: 100000, pricePerKm: 8000, effectiveFrom: '2026-11-01T00:00' });
  return form;
}

describe('pricing config form', () => {
  it('rejects negative prices and missing required fields', () => {
    const form = pricingConfigForm();
    expect(form.valid).toBe(false);
    const f = valid();
    expect(f.valid).toBe(true);
    f.patchValue({ basePrice: -1 });
    expect(f.controls.basePrice.hasError('min')).toBe(true);
    f.patchValue({ basePrice: 100000, minimumPrice: -5 });
    expect(f.valid).toBe(false);
  });

  it('requires the end of the effective period to be after the start', () => {
    const f = valid();
    f.patchValue({ effectiveTo: '2026-10-31T00:00' });
    expect(f.hasError('period')).toBe(true);
    f.patchValue({ effectiveTo: '2026-12-31T00:00' });
    expect(f.hasError('period')).toBe(false);
  });

  it('validates time rules and extra fees', () => {
    const rule = timeRuleForm();
    rule.patchValue({ name: 'Ban đêm', startTime: '22:00', endTime: '22:00', feeType: 'PERCENTAGE', percentage: 20 });
    expect(rule.hasError('sameTime')).toBe(true);
    rule.patchValue({ endTime: '06:00', daysOfWeek: 'MONDAY' });
    expect(rule.controls.daysOfWeek.hasError('pattern')).toBe(true);
    rule.patchValue({ daysOfWeek: 'SAT,SUN' });
    expect(rule.valid).toBe(true);
    rule.patchValue({ percentage: 0 });
    expect(rule.hasError('feeValue')).toBe(true);
    const fee = extraFeeForm();
    fee.patchValue({ code: 'fuel', name: 'Nhiên liệu', amount: 10000 });
    expect(fee.controls.code.hasError('pattern')).toBe(true);
    fee.patchValue({ code: 'FUEL' });
    expect(fee.valid).toBe(true);
  });

  it('builds the payload with ISO times and only the relevant fee value', () => {
    const f = valid();
    const rule = timeRuleForm();
    rule.patchValue({ name: 'Ban đêm', startTime: '22:00', endTime: '06:00', feeType: 'PERCENTAGE', percentage: 20, amount: 999 });
    f.controls.timeRules.push(rule);
    const payload = toConfigPayload(f, 3);
    expect(payload.effectiveFrom).toBe(new Date('2026-11-01T00:00').toISOString());
    expect(payload.effectiveTo).toBeNull();
    expect(payload.timeRules[0]).toMatchObject({ daysOfWeek: null, percentage: 20, amount: 0 });
    expect(payload.version).toBe(3);
  });
});
