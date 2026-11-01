import { Component, computed, input } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ServiceDefinition, ServiceGroup } from '../../../core/api/catalog.api';
import { fieldError } from '../../../shared/form-utils';
import { ShipmentForm } from '../shipment-form';

const GROUPS: { code: ServiceGroup; title: string }[] = [
  { code: 'LOADING', title: 'Bốc xếp' }, { code: 'SITE', title: 'Điều kiện địa điểm' }, { code: 'GOODS', title: 'Đặc điểm hàng hóa' }, { code: 'OTHER', title: 'Yêu cầu khác' },
];

/** Rendered entirely from the service catalog: adding a service in the database needs no frontend change. */
@Component({
  selector: 'app-services-step',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <div [formGroup]="form()">
    @for (group of groups(); track group.code) {
      <section class="card" formGroupName="services">
        <h2>{{ group.title }}</h2>
        <div class="service-grid">
          @for (s of group.items; track s.code) {
            @switch (s.valueType) {
              @case ('BOOLEAN') {
                <label class="checkbox"><input type="checkbox" [formControlName]="s.code" (change)="exclusive(s)" /> {{ s.name }}</label>
              }
              @case ('OPTION') {
                <div class="field"><label [for]="s.code">{{ s.name }}@if (s.appliesTo === 'BOTH') { <span class="muted"> (cho cả đơn)</span> }</label>
                  <select [id]="s.code" [formControlName]="s.code" [attr.disabled]="usedAtStops(s.code) ? true : null"><option [ngValue]="null">Không cần hỗ trợ</option>
                    @for (o of s.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                  </select>
                  @if (usedAtStops(s.code)) { <span class="muted small">Đã chọn riêng tại {{ usedAtStops(s.code) }} điểm ở bước Địa điểm; không chọn thêm cho cả đơn để tránh tính phí trùng.</span> }
                  @else if (s.appliesTo === 'BOTH') { <span class="muted small">Tính một lần cho cả đơn. Có thể chọn riêng cho từng điểm ở bước Địa điểm.</span> }</div>
              }
              @case ('NUMBER') {
                <div class="field" [class.invalid]="err(s.code)"><label [for]="s.code">{{ s.name }}@if (s.description) { <span class="muted"> ({{ s.description }})</span> }</label>
                  <input [id]="s.code" type="number" [min]="s.minValue ?? 1" [max]="s.maxValue ?? 100" step="1" [formControlName]="s.code" />
                  @if (err(s.code); as e) { <span class="error">{{ e }}</span> }</div>
              }
              @case ('TEXT') {
                <div class="field wide" [class.invalid]="err(s.code)"><label [for]="s.code">{{ s.name }}</label>
                  <textarea [id]="s.code" rows="2" maxlength="500" [formControlName]="s.code"></textarea>
                  @if (err(s.code); as e) { <span class="error">{{ e }}</span> }</div>
              }
            }
          }
        </div>
      </section>
    }
    <section class="card">
      <h2>Ghi chú cho đơn hàng</h2>
      <div class="field"><label for="customerNote">Ghi chú</label><textarea id="customerNote" rows="3" maxlength="1000" formControlName="customerNote"></textarea></div>
    </section>
    <p class="hint muted">Dịch vụ bổ sung chưa được tính phí ở bước này; phụ phí (nếu có) sẽ hiển thị khi báo giá.</p>
  </div>`,
  styles: [`.service-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:4px 18px;align-items:start}.wide{grid-column:1/-1}.small{font-size:12px}`],
})
export class ServicesStepComponent {
  readonly form = input.required<ShipmentForm>();
  readonly catalog = input<ServiceDefinition[]>([]);
  protected readonly groups = computed(() => GROUPS.map(g => ({ ...g, items: this.catalog().filter(s => s.group === g.code) })).filter(g => g.items.length));

  /** Unticks the other services in the same exclusive group (e.g. elevator available / unavailable). */
  exclusive(selected: ServiceDefinition): void {
    if (!selected.exclusiveGroup || !this.form().controls.services.get(selected.code)?.value) return;
    for (const s of this.catalog()) if (s.code !== selected.code && s.exclusiveGroup === selected.exclusiveGroup) this.form().controls.services.get(s.code)?.setValue(false);
  }

  /** How many stops order this service individually (then the order-wide choice is not allowed). */
  usedAtStops(code: string): number {
    return this.form().controls.stops.controls.filter(st => !!st.controls.services.value[code]).length;
  }

  err(code: string): string | null {
    return fieldError(this.form().controls.services.get(code), { min: 'Giá trị nằm ngoài phạm vi', max: 'Giá trị nằm ngoài phạm vi', maxlength: 'Tối đa 500 ký tự' });
  }
}
