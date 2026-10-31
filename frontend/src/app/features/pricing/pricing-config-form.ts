import { AbstractControl, FormArray, FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { PricingConfig, PricingConfigPayload } from '../../core/api/pricing.api';
import { toIsoInstant, toLocalInput } from '../shipments/shipment-form';

const money = (value: number | null = null, required = true) =>
  new FormControl<number | null>(value, { validators: [...(required ? [Validators.required] : []), Validators.min(0), Validators.max(999_999_999_999)] });

/** End must be after start (both local datetime strings). */
export const periodValidator: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
  const from = g.get('effectiveFrom')?.value as string;
  const to = g.get('effectiveTo')?.value as string;
  return from && to && new Date(to) <= new Date(from) ? { period: true } : null;
};

const feeValidator: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
  const type = g.get('feeType')?.value;
  const amount = Number(g.get('amount')?.value) || 0;
  const pct = Number(g.get('percentage')?.value) || 0;
  if (type === 'PERCENTAGE') return pct > 0 && amount === 0 ? null : { feeValue: true };
  return amount > 0 && pct === 0 ? null : { feeValue: true };
};

export function timeRuleForm() {
  return new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    daysOfWeek: new FormControl('', { nonNullable: true, validators: [Validators.pattern(/^((MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*)?$/)] }),
    startTime: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    endTime: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    feeType: new FormControl<'FIXED' | 'PERCENTAGE'>('PERCENTAGE', { nonNullable: true }),
    amount: money(0), percentage: new FormControl<number | null>(0, { validators: [Validators.min(0), Validators.max(1000)] }),
  }, { validators: [feeValidator, (g: AbstractControl) => g.get('startTime')?.value && g.get('startTime')?.value === g.get('endTime')?.value ? { sameTime: true } : null] });
}

export function extraFeeForm() {
  return new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-Z0-9_]{2,40}$/)] }),
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    feeType: new FormControl<'FIXED' | 'PERCENTAGE'>('FIXED', { nonNullable: true }),
    amount: money(0), percentage: new FormControl<number | null>(0, { validators: [Validators.min(0), Validators.max(1000)] }),
  }, { validators: feeValidator });
}

export type TimeRuleForm = ReturnType<typeof timeRuleForm>;
export type ExtraFeeForm = ReturnType<typeof extraFeeForm>;

export function pricingConfigForm() {
  return new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    vehicleTypeCode: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    basePrice: money(), pricePerKm: money(), includedDistanceKm: money(0), includedWeightKg: money(0), pricePerExtraKg: money(0),
    includedVolumeM3: money(0), pricePerExtraM3: money(0), additionalStopPrice: money(0), minimumPrice: money(0),
    effectiveFrom: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    effectiveTo: new FormControl('', { nonNullable: true }),
    timeRules: new FormArray<TimeRuleForm>([]),
    extraFees: new FormArray<ExtraFeeForm>([]),
  }, { validators: periodValidator });
}

export type PricingConfigForm = ReturnType<typeof pricingConfigForm>;

export function toConfigPayload(form: PricingConfigForm, version?: number): PricingConfigPayload {
  const v = form.getRawValue();
  const n = (x: number | null) => Number(x ?? 0);
  return {
    name: v.name.trim(), vehicleTypeCode: v.vehicleTypeCode, basePrice: n(v.basePrice), pricePerKm: n(v.pricePerKm), includedDistanceKm: n(v.includedDistanceKm),
    includedWeightKg: n(v.includedWeightKg), pricePerExtraKg: n(v.pricePerExtraKg), includedVolumeM3: n(v.includedVolumeM3), pricePerExtraM3: n(v.pricePerExtraM3),
    additionalStopPrice: n(v.additionalStopPrice), minimumPrice: n(v.minimumPrice),
    effectiveFrom: toIsoInstant(v.effectiveFrom)!, effectiveTo: toIsoInstant(v.effectiveTo),
    timeRules: v.timeRules.map(t => ({ name: t.name.trim(), daysOfWeek: t.daysOfWeek.trim() || null, startTime: t.startTime, endTime: t.endTime, feeType: t.feeType,
      amount: t.feeType === 'FIXED' ? n(t.amount) : 0, percentage: t.feeType === 'PERCENTAGE' ? n(t.percentage) : 0 })),
    extraFees: v.extraFees.map(e => ({ code: e.code.trim(), name: e.name.trim(), feeType: e.feeType,
      amount: e.feeType === 'FIXED' ? n(e.amount) : 0, percentage: e.feeType === 'PERCENTAGE' ? n(e.percentage) : 0 })),
    ...(version === undefined ? {} : { version }),
  };
}

export function patchConfig(form: PricingConfigForm, c: PricingConfig): void {
  const { timeRules: _rules, extraFees: _fees, ...scalars } = c;
  form.patchValue({ ...scalars, effectiveFrom: toLocalInput(c.effectiveFrom), effectiveTo: toLocalInput(c.effectiveTo) });
  form.controls.timeRules.clear();
  for (const t of c.timeRules) {
    const g = timeRuleForm();
    g.patchValue({ ...t, daysOfWeek: t.daysOfWeek ?? '', startTime: t.startTime.slice(0, 5), endTime: t.endTime.slice(0, 5) });
    form.controls.timeRules.push(g);
  }
  form.controls.extraFees.clear();
  for (const e of c.extraFees) { const g = extraFeeForm(); g.patchValue(e); form.controls.extraFees.push(g); }
  form.markAsPristine();
}
