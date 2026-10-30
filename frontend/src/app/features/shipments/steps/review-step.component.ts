import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { BodyType, CatalogVehicleType, ItemCategory, ServiceDefinition } from '../../../core/api/catalog.api';
import { DraftPayload } from '../../../core/api/shipment.api';
import { computeTotals } from '../shipment-form';
import { PRICE_PENDING, formatKg, formatM3 } from '../shipment-labels';

@Component({
  selector: 'app-review-step',
  standalone: true,
  imports: [DatePipe],
  template: `
  @let p = payload();
  @let t = totals();
  <section class="card">
    <h2>Lộ trình</h2>
    @for (s of p.stops; track $index) {
      <div class="review-stop">
        <span class="badge" [class.badge--approved]="s.stopType === 'PICKUP'">{{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</span>
        <div><strong>{{ s.contactName }}</strong> · {{ s.contactPhone }}<br><span class="muted">{{ address(s) }}</span>
          @if (s.note) { <br><em class="muted">Ghi chú: {{ s.note }}</em> }</div>
      </div>
    }
    <dl class="dl" style="margin-top:14px">
      <dt>Lấy hàng lúc</dt><dd>{{ p.pickupTime ? (p.pickupTime | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
      <dt>Giao trước</dt><dd>{{ p.deliveryDeadline ? (p.deliveryDeadline | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
    </dl>
  </section>
  <section class="card">
    <h2>Hàng hóa</h2>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Tên hàng</th><th>Loại</th><th>Số kiện</th><th>Kg/kiện</th><th>Kích thước (cm)</th></tr></thead>
      <tbody>@for (i of p.items; track $index) {
        <tr><td>{{ i.itemName }}</td><td>{{ categoryName(i.category) }}</td><td>{{ i.quantity }}</td><td>{{ i.weightPerUnitKg }}</td>
          <td>{{ i.lengthCm ? i.lengthCm + '×' + i.widthCm + '×' + i.heightCm : '—' }}</td></tr>
      }</tbody>
    </table></div>
    <dl class="dl" style="margin-top:14px">
      <dt>Tổng số kiện</dt><dd>{{ t.quantity }}</dd><dt>Tổng trọng lượng</dt><dd>{{ kg(t.weightKg) }}</dd><dt>Tổng thể tích</dt><dd>{{ m3(t.volumeM3) }}</dd>
    </dl>
  </section>
  <section class="card">
    <h2>Phương tiện & dịch vụ</h2>
    <dl class="dl">
      <dt>Loại xe</dt><dd>{{ vehicleName(p.vehicleTypeCode) }}@if (p.vehicleCount > 1) { × {{ p.vehicleCount }} }</dd>
      <dt>Loại thùng</dt><dd>{{ bodyName(p.bodyTypeCode) }}</dd>
      @if (p.minimumPayloadKg) { <dt>Tải trọng tối thiểu</dt><dd>{{ kg(p.minimumPayloadKg) }}</dd> }
      <dt>Dịch vụ bổ sung</dt><dd>@for (s of p.services; track s.serviceCode; let last = $last) { {{ serviceText(s.serviceCode, s.serviceValue) }}@if (!last) {, } } @empty { Không }</dd>
      <dt>Ghi chú</dt><dd>{{ p.customerNote || '—' }}</dd>
    </dl>
  </section>
  <section class="card price">
    <h2>Giá cước</h2>
    <p>{{ pricePending }}</p>
  </section>`,
  styles: [`.review-stop{display:flex;gap:12px;align-items:flex-start;padding:10px 0;border-bottom:1px solid #eef1ea;font-size:14px}.review-stop .badge{min-width:82px;text-align:center}.price p{margin:0;color:var(--tg-muted)}`],
})
export class ReviewStepComponent {
  readonly payload = input.required<DraftPayload>();
  readonly categories = input<ItemCategory[]>([]);
  readonly vehicleTypes = input<CatalogVehicleType[]>([]);
  readonly bodyTypes = input<BodyType[]>([]);
  readonly services = input<ServiceDefinition[]>([]);
  protected readonly pricePending = PRICE_PENDING;
  protected readonly kg = formatKg;
  protected readonly m3 = formatM3;

  totals() { return computeTotals(this.payload().items); }
  address(s: DraftPayload['stops'][number]): string { return [s.addressDetail, s.ward, s.district, s.province].filter(Boolean).join(', '); }
  categoryName(code: string): string { return this.categories().find(c => c.code === code)?.name ?? code; }
  vehicleName(code: string | null): string { return code ? this.vehicleTypes().find(v => v.code === code)?.name ?? code : 'Để hệ thống gợi ý'; }
  bodyName(code: string | null): string { return code ? this.bodyTypes().find(b => b.code === code)?.name ?? code : 'Không yêu cầu'; }
  serviceText(code: string, value: string): string {
    const def = this.services().find(s => s.code === code);
    if (!def) return code;
    if (def.valueType === 'OPTION') return `${def.name}: ${def.options.find(o => o.value === value)?.label ?? value}`;
    if (def.valueType === 'NUMBER') return `${def.name}: ${value}`;
    if (def.valueType === 'TEXT') return `${def.name}: “${value}”`;
    return def.name;
  }
}
