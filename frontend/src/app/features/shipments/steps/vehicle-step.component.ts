import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { merge, startWith } from 'rxjs';
import { BodyType, CatalogVehicleType } from '../../../core/api/catalog.api';
import { fieldError } from '../../../shared/form-utils';
import { ShipmentForm, computeTotals } from '../shipment-form';
import { formatKg } from '../shipment-labels';

@Component({
  selector: 'app-vehicle-step',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <div [formGroup]="form()">
    <section class="card" formGroupName="vehicle">
      <h2>Yêu cầu phương tiện</h2>
      <p class="muted">Tổng trọng lượng hàng hiện tại: <strong>{{ kg(weight()) }}</strong></p>
      <div class="vehicle-grid" role="radiogroup" aria-label="Loại xe">
        <label class="vehicle-option" [class.selected]="!selectedCode()">
          <input type="radio" formControlName="vehicleTypeCode" value="" /><span><strong>Để hệ thống gợi ý</strong><small>Theo tổng trọng lượng</small></span>
        </label>
        @for (v of vehicleTypes(); track v.code) {
          <label class="vehicle-option" [class.selected]="selectedCode() === v.code" [class.too-small]="v.maxPayloadKg * count() < weight()">
            <input type="radio" formControlName="vehicleTypeCode" [value]="v.code" />
            <span><strong>{{ v.name }}</strong><small>Tải trọng danh nghĩa {{ kg(v.maxPayloadKg) }}@if (v.cargoLengthCm) { · thùng ~{{ v.cargoLengthCm }}×{{ v.cargoWidthCm }}×{{ v.cargoHeightCm }} cm }</small></span>
          </label>
        }
      </div>
      @for (w of warnings(); track w) { <div class="alert alert-warn" role="status">{{ w }}</div> }
      <p class="hint muted">Tải trọng danh nghĩa chỉ để tham khảo. Phương tiện thực tế sẽ được kiểm tra tải trọng và quy định khi ghép tài xế.</p>
      <div class="grid-3">
        <div class="field"><label for="bodyType">Loại thùng xe</label>
          <select id="bodyType" formControlName="bodyTypeCode"><option value="">Không yêu cầu</option>
            @for (b of bodyTypes(); track b.code) { @if (b.code !== 'ANY') { <option [value]="b.code">{{ b.name }}</option> } }
          </select></div>
        <div class="field" [class.invalid]="err('vehicleCount')"><label for="vehicleCount">Số xe</label>
          <input id="vehicleCount" type="number" min="1" max="20" formControlName="vehicleCount" />
          @if (err('vehicleCount'); as e) { <span class="error">{{ e }}</span> }</div>
        <div class="field" [class.invalid]="err('minimumPayloadKg')"><label for="minimumPayloadKg">Tải trọng tối thiểu (kg)</label>
          <input id="minimumPayloadKg" type="number" min="0.01" step="0.01" formControlName="minimumPayloadKg" />
          @if (err('minimumPayloadKg'); as e) { <span class="error">{{ e }}</span> }</div>
      </div>
      <details><summary class="muted">Kích thước thùng tối thiểu (không bắt buộc)</summary>
        <div class="grid-3" style="margin-top:12px">
          <div class="field"><label for="minL">Dài (cm)</label><input id="minL" type="number" min="0.01" formControlName="minCargoLengthCm" /></div>
          <div class="field"><label for="minW">Rộng (cm)</label><input id="minW" type="number" min="0.01" formControlName="minCargoWidthCm" /></div>
          <div class="field"><label for="minH">Cao (cm)</label><input id="minH" type="number" min="0.01" formControlName="minCargoHeightCm" /></div>
        </div>
      </details>
    </section>
  </div>`,
  styles: [`.vehicle-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:10px;margin:14px 0}
  .vehicle-option{display:flex;gap:10px;align-items:flex-start;border:1px solid #cfd7c8;border-radius:6px;padding:12px;cursor:pointer;background:#fff}
  .vehicle-option.selected{border-color:var(--tg-dark);box-shadow:0 0 0 1px var(--tg-dark)}.vehicle-option.too-small{opacity:.7}
  .vehicle-option small{display:block;color:var(--tg-muted);font-size:12px;margin-top:4px}.vehicle-option input{margin-top:3px}details summary{cursor:pointer;font-size:13px;margin-bottom:6px}`],
})
export class VehicleStepComponent implements OnInit {
  readonly form = input.required<ShipmentForm>();
  readonly vehicleTypes = input<CatalogVehicleType[]>([]);
  readonly bodyTypes = input<BodyType[]>([]);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly weight = signal(0);
  protected readonly selectedCode = signal('');
  protected readonly count = signal(1);
  protected readonly minimum = signal<number | null>(null);
  protected readonly kg = formatKg;

  /** Mirrors the backend's advisory checks; never states that a vehicle is suitable. */
  protected readonly warnings = computed(() => {
    const out: string[] = [];
    const weight = this.weight();
    const min = this.minimum();
    if (min !== null && min < weight) out.push(`Tải trọng tối thiểu ${formatKg(min)} thấp hơn tổng trọng lượng hàng ${formatKg(weight)}.`);
    const type = this.vehicleTypes().find(v => v.code === this.selectedCode());
    if (type && type.maxPayloadKg * this.count() < weight)
      out.push(`Tổng trọng lượng ${formatKg(weight)} vượt tải trọng danh nghĩa ${formatKg(type.maxPayloadKg * this.count())} của ${this.count()} × ${type.name}. Hãy chọn xe lớn hơn hoặc tăng số xe.`);
    return out;
  });

  ngOnInit(): void {
    const f = this.form();
    merge(f.controls.items.valueChanges, f.controls.vehicle.valueChanges).pipe(startWith(null), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.weight.set(computeTotals(f.controls.items.getRawValue()).weightKg);
      const v = f.controls.vehicle.getRawValue();
      this.selectedCode.set(v.vehicleTypeCode);
      this.count.set(Number(v.vehicleCount) || 1);
      this.minimum.set(v.minimumPayloadKg === null || (v.minimumPayloadKg as unknown) === '' ? null : Number(v.minimumPayloadKg));
    });
  }

  err(name: 'vehicleCount' | 'minimumPayloadKg'): string | null {
    return fieldError(this.form().controls.vehicle.controls[name], { required: 'Trường này là bắt buộc', min: 'Giá trị không hợp lệ', max: 'Tối đa 20 xe' });
  }
}
