import { AbstractControl, FormArray, FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { ServiceDefinition } from '../../core/api/catalog.api';
import { DraftPayload, ShipmentDetail, StopType } from '../../core/api/shipment.api';
import { VN_PHONE_PATTERN } from '../../shared/form-utils';

export type StopForm = FormGroup<{
  stopType: FormControl<StopType>; contactName: FormControl<string>; contactPhone: FormControl<string>; province: FormControl<string>;
  district: FormControl<string>; ward: FormControl<string>; addressDetail: FormControl<string>;
  latitude: FormControl<number | null>; longitude: FormControl<number | null>; note: FormControl<string>;
}>;
export type ItemForm = FormGroup<{
  itemName: FormControl<string>; category: FormControl<string>; description: FormControl<string>; quantity: FormControl<number | null>;
  weightPerUnitKg: FormControl<number | null>; lengthCm: FormControl<number | null>; widthCm: FormControl<number | null>; heightCm: FormControl<number | null>;
}>;
export type ShipmentForm = FormGroup<{
  schedule: FormGroup<{ pickupTime: FormControl<string>; deliveryDeadline: FormControl<string> }>;
  stops: FormArray<StopForm>;
  items: FormArray<ItemForm>;
  vehicle: FormGroup<{
    vehicleTypeCode: FormControl<string>; bodyTypeCode: FormControl<string>; vehicleCount: FormControl<number | null>; minimumPayloadKg: FormControl<number | null>;
    minCargoLengthCm: FormControl<number | null>; minCargoWidthCm: FormControl<number | null>; minCargoHeightCm: FormControl<number | null>;
  }>;
  /** One control per catalog service code; built when the catalog arrives. */
  services: FormGroup<Record<string, FormControl<string | number | boolean | null>>>;
  customerNote: FormControl<string>;
}>;

export const STEP_LABELS = ['Địa điểm', 'Hàng hóa', 'Phương tiện', 'Dịch vụ', 'Xác nhận'] as const;

const text = (value = '', ...validators: ValidatorFn[]) => new FormControl(value, { nonNullable: true, validators });
const num = (value: number | null = null, ...validators: ValidatorFn[]) => new FormControl<number | null>(value, { validators });
const positive = Validators.min(0.01);

/** Dimensions must be given all together or not at all (the server rejects partial sizes). */
const dimensionsTogether: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const values = ['lengthCm', 'widthCm', 'heightCm'].map(k => group.get(k)?.value);
  const filled = values.filter(v => v !== null && v !== '' && v !== undefined).length;
  return filled === 0 || filled === 3 ? null : { partialDimensions: true };
};

const coordinatesTogether: ValidatorFn = (group: AbstractControl): ValidationErrors | null =>
  (group.get('latitude')?.value === null) === (group.get('longitude')?.value === null) ? null : { partialCoordinates: true };

const deadlineAfterPickup: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const pickup = group.get('pickupTime')?.value as string;
  const deadline = group.get('deliveryDeadline')?.value as string;
  return pickup && deadline && new Date(deadline) <= new Date(pickup) ? { deadlineBeforePickup: true } : null;
};

const stopsComplete: ValidatorFn = (array: AbstractControl): ValidationErrors | null => {
  const types = (array as FormArray<StopForm>).controls.map(c => c.controls.stopType.value);
  if (!types.includes('PICKUP')) return { noPickup: true };
  if (!types.includes('DELIVERY')) return { noDelivery: true };
  return types[0] === 'PICKUP' ? null : { pickupFirst: true };
};

export function stopForm(type: StopType): StopForm {
  return new FormGroup({
    stopType: new FormControl<StopType>(type, { nonNullable: true }),
    contactName: text('', Validators.required, Validators.maxLength(100)),
    contactPhone: text('', Validators.required, Validators.pattern(VN_PHONE_PATTERN)),
    province: text('', Validators.required, Validators.maxLength(100)),
    district: text('', Validators.maxLength(100)),
    ward: text('', Validators.maxLength(100)),
    addressDetail: text('', Validators.required, Validators.maxLength(255)),
    latitude: num(null, Validators.min(-90), Validators.max(90)),
    longitude: num(null, Validators.min(-180), Validators.max(180)),
    note: text('', Validators.maxLength(500)),
  }, { validators: coordinatesTogether });
}

export function itemForm(): ItemForm {
  return new FormGroup({
    itemName: text('', Validators.required, Validators.maxLength(150)),
    category: text('', Validators.required),
    description: text('', Validators.maxLength(500)),
    quantity: num(1, Validators.required, Validators.min(1), Validators.max(100000)),
    weightPerUnitKg: num(null, Validators.required, positive, Validators.max(99999999)),
    lengthCm: num(null, positive, Validators.max(999999)),
    widthCm: num(null, positive, Validators.max(999999)),
    heightCm: num(null, positive, Validators.max(999999)),
  }, { validators: dimensionsTogether });
}

export function shipmentForm(): ShipmentForm {
  return new FormGroup({
    schedule: new FormGroup({ pickupTime: text('', Validators.required), deliveryDeadline: text('') }, { validators: deadlineAfterPickup }),
    stops: new FormArray([stopForm('PICKUP'), stopForm('DELIVERY')], { validators: stopsComplete }),
    items: new FormArray([itemForm()], { validators: Validators.minLength(1) }),
    vehicle: new FormGroup({
      vehicleTypeCode: text(''), bodyTypeCode: text(''), vehicleCount: num(1, Validators.required, Validators.min(1), Validators.max(20)),
      minimumPayloadKg: num(null, positive), minCargoLengthCm: num(null, positive), minCargoWidthCm: num(null, positive), minCargoHeightCm: num(null, positive),
    }),
    services: new FormGroup<Record<string, FormControl<string | number | boolean | null>>>({}),
    customerNote: text('', Validators.maxLength(1000)),
  });
}

/** Adds one control per catalog service, keeping values already entered. */
export function ensureServiceControls(form: ShipmentForm, catalog: ServiceDefinition[]): void {
  for (const def of catalog) {
    if (form.controls.services.contains(def.code)) continue;
    const initial = def.valueType === 'BOOLEAN' ? false : null;
    const validators = def.valueType === 'NUMBER' ? [Validators.min(def.minValue ?? 1), Validators.max(def.maxValue ?? 100)] : def.valueType === 'TEXT' ? [Validators.maxLength(500)] : [];
    form.controls.services.addControl(def.code, new FormControl<string | number | boolean | null>(initial, { validators }));
  }
}

export interface Totals { quantity: number; weightKg: number; volumeM3: number; itemsWithoutSize: number; }

/**
 * Reference totals for the form (same formulas as the backend). Computed in integer hundredths/millionths to avoid
 * float drift; the server recomputes with BigDecimal and its figures are the ones saved.
 */
export function computeTotals(items: { quantity: number | null; weightPerUnitKg: number | null; lengthCm: number | null; widthCm: number | null; heightCm: number | null }[]): Totals {
  let quantity = 0;
  let weightHundredths = 0;
  let volumeCm3 = 0;
  let itemsWithoutSize = 0;
  for (const item of items) {
    const qty = Number(item.quantity) || 0;
    quantity += qty;
    weightHundredths += qty * Math.round((Number(item.weightPerUnitKg) || 0) * 100);
    if (item.lengthCm && item.widthCm && item.heightCm) volumeCm3 += qty * Number(item.lengthCm) * Number(item.widthCm) * Number(item.heightCm);
    else itemsWithoutSize++;
  }
  return { quantity, weightKg: weightHundredths / 100, volumeM3: Math.round(volumeCm3) / 1_000_000, itemsWithoutSize };
}

const blankToNull = (value: string | null | undefined) => (value ?? '').trim() || null;
const numOrNull = (value: number | null | undefined) => (value === null || value === undefined || (value as unknown) === '' ? null : Number(value));

/** `datetime-local` (browser local time) ⇄ ISO instant. */
export function toIsoInstant(local: string): string | null { return local ? new Date(local).toISOString() : null; }
export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function toPayload(form: ShipmentForm, catalog: ServiceDefinition[], version?: number): DraftPayload {
  const v = form.getRawValue();
  const services = catalog.flatMap(def => {
    const raw = v.services[def.code];
    const value = typeof raw === 'string' ? raw.trim() : raw;
    if (value === null || value === undefined || value === '' || value === false) return [];
    return [{ serviceCode: def.code, serviceValue: def.valueType === 'BOOLEAN' ? 'true' : String(value).trim() }];
  });
  return {
    pickupTime: toIsoInstant(v.schedule.pickupTime), deliveryDeadline: toIsoInstant(v.schedule.deliveryDeadline),
    vehicleTypeCode: blankToNull(v.vehicle.vehicleTypeCode), bodyTypeCode: blankToNull(v.vehicle.bodyTypeCode), vehicleCount: Number(v.vehicle.vehicleCount) || 1,
    minimumPayloadKg: numOrNull(v.vehicle.minimumPayloadKg), minCargoLengthCm: numOrNull(v.vehicle.minCargoLengthCm),
    minCargoWidthCm: numOrNull(v.vehicle.minCargoWidthCm), minCargoHeightCm: numOrNull(v.vehicle.minCargoHeightCm),
    customerNote: blankToNull(v.customerNote),
    stops: v.stops.map((s, i) => ({
      stopType: s.stopType, sequenceNo: i + 1, contactName: s.contactName.trim(), contactPhone: s.contactPhone.trim(), province: s.province.trim(),
      district: blankToNull(s.district), ward: blankToNull(s.ward), addressDetail: s.addressDetail.trim(),
      latitude: numOrNull(s.latitude), longitude: numOrNull(s.longitude), note: blankToNull(s.note),
    })),
    items: v.items.map(i => ({
      itemName: i.itemName.trim(), category: i.category, description: blankToNull(i.description), quantity: Number(i.quantity),
      weightPerUnitKg: Number(i.weightPerUnitKg), lengthCm: numOrNull(i.lengthCm), widthCm: numOrNull(i.widthCm), heightCm: numOrNull(i.heightCm),
    })),
    services,
    ...(version === undefined ? {} : { version }),
  };
}

/** Loads a saved draft back into the wizard. */
export function patchFromDetail(form: ShipmentForm, detail: ShipmentDetail, catalog: ServiceDefinition[]): void {
  form.controls.stops.clear();
  for (const s of detail.stops) {
    const g = stopForm(s.stopType);
    g.setValue({ stopType: s.stopType, contactName: s.contactName, contactPhone: s.contactPhone, province: s.province, district: s.district ?? '', ward: s.ward ?? '',
      addressDetail: s.addressDetail, latitude: s.latitude, longitude: s.longitude, note: s.note ?? '' });
    form.controls.stops.push(g);
  }
  form.controls.items.clear();
  for (const i of detail.items) {
    const g = itemForm();
    g.setValue({ itemName: i.itemName, category: i.category, description: i.description ?? '', quantity: i.quantity, weightPerUnitKg: i.weightPerUnitKg,
      lengthCm: i.lengthCm, widthCm: i.widthCm, heightCm: i.heightCm });
    form.controls.items.push(g);
  }
  form.controls.schedule.setValue({ pickupTime: toLocalInput(detail.pickupTime), deliveryDeadline: toLocalInput(detail.deliveryDeadline) });
  const veh = detail.vehicle;
  form.controls.vehicle.setValue({ vehicleTypeCode: veh.vehicleTypeCode ?? '', bodyTypeCode: veh.bodyTypeCode ?? '', vehicleCount: veh.vehicleCount,
    minimumPayloadKg: veh.minimumPayloadKg, minCargoLengthCm: veh.minCargoLengthCm, minCargoWidthCm: veh.minCargoWidthCm, minCargoHeightCm: veh.minCargoHeightCm });
  form.controls.customerNote.setValue(detail.customerNote ?? '');
  ensureServiceControls(form, catalog);
  for (const def of catalog) {
    const selected = detail.services.find(s => s.serviceCode === def.code);
    const value = !selected ? (def.valueType === 'BOOLEAN' ? false : null) : def.valueType === 'BOOLEAN' ? true : def.valueType === 'NUMBER' ? Number(selected.serviceValue) : selected.serviceValue;
    form.controls.services.controls[def.code].setValue(value);
  }
  form.markAsPristine();
}

/** Server field keys ("stops[1].contactPhone", "services[0].serviceValue", …) → form controls and wizard step. */
export function applyServerErrors(form: ShipmentForm, errors: Record<string, string>, payload: DraftPayload): { step: number | null; unmatched: string[] } {
  const unmatched: string[] = [];
  let step: number | null = null;
  const at = (s: number) => { step = step === null ? s : Math.min(step, s); };
  for (const [key, message] of Object.entries(errors)) {
    const match = /^(stops|items|services)\[(\d+)]\.(\w+)$/.exec(key);
    let control: AbstractControl | null = null;
    if (match) {
      const [, list, index, field] = match;
      if (list === 'stops') { control = form.controls.stops.at(+index)?.get(field) ?? null; at(0); }
      if (list === 'items') { control = form.controls.items.at(+index)?.get(field) ?? null; at(1); }
      if (list === 'services') { const code = payload.services[+index]?.serviceCode; control = code ? form.controls.services.get(code) : null; at(3); }
    } else if (key === 'pickupTime' || key === 'deliveryDeadline') { control = form.controls.schedule.get(key); at(0); }
    else if (key.startsWith('vehicle') || key === 'bodyTypeCode' || key.startsWith('minimum') || key.startsWith('minCargo')) { control = form.controls.vehicle.get(key); at(2); }
    else if (key === 'stops') at(0);
    else if (key === 'items') at(1);
    if (control) { control.setErrors({ ...(control.errors ?? {}), server: message }); control.markAsTouched(); }
    else unmatched.push(message);
  }
  return { step, unmatched };
}
