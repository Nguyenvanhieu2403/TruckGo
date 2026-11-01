import { Component, input } from '@angular/core';
import { AbstractControl, ReactiveFormsModule } from '@angular/forms';
import { fieldError } from '../../../shared/form-utils';
import { ShipmentForm, StopForm, stopForm } from '../shipment-form';
import { ServiceDefinition, allowedAtStop } from '../../../core/api/catalog.api';
import { AddressSearchComponent } from './address-search.component';

const MESSAGES = { required: 'Trường này là bắt buộc', maxlength: 'Giá trị quá dài', pattern: 'Số điện thoại di động không hợp lệ', min: 'Giá trị ngoài phạm vi', max: 'Giá trị ngoài phạm vi' };

@Component({
  selector: 'app-stops-step',
  standalone: true,
  imports: [ReactiveFormsModule, AddressSearchComponent],
  template: `
  <div [formGroup]="form()">
    <section class="card" formGroupName="schedule">
      <h2>Thời gian</h2>
      <div class="grid-2">
        <div class="field" [class.invalid]="err(form().controls.schedule.controls.pickupTime)">
          <label for="pickupTime">Thời gian lấy hàng</label><input id="pickupTime" type="datetime-local" formControlName="pickupTime" />
          @if (err(form().controls.schedule.controls.pickupTime); as e) { <span class="error">{{ e }}</span> }
        </div>
        <div class="field" [class.invalid]="err(form().controls.schedule.controls.deliveryDeadline) || form().controls.schedule.hasError('deadlineBeforePickup')">
          <label for="deliveryDeadline">Giao trước (không bắt buộc)</label><input id="deliveryDeadline" type="datetime-local" formControlName="deliveryDeadline" />
          @if (err(form().controls.schedule.controls.deliveryDeadline); as e) { <span class="error">{{ e }}</span> }
          @else if (form().controls.schedule.hasError('deadlineBeforePickup')) { <span class="error">Thời gian giao phải sau thời gian lấy hàng</span> }
        </div>
      </div>
    </section>
    @if (stopsError(); as e) { <div class="alert alert-error" role="alert">{{ e }}</div> }
    <div formArrayName="stops">
      @for (stop of form().controls.stops.controls; track stop; let i = $index) {
        <section class="card stop-card" [formGroupName]="i">
          <div class="stop-head">
            <h2>{{ stop.controls.stopType.value === 'PICKUP' ? 'Điểm lấy hàng' : 'Điểm giao hàng ' + deliveryNumber(i) }}</h2>
            @if (canRemove(i)) { <button class="btn btn-ghost" type="button" (click)="remove(i)">Xóa điểm</button> }
          </div>
          <div class="grid-2">
            <div class="field" [class.invalid]="err(stop.controls.contactName)">
              <label [for]="'contactName' + i">{{ stop.controls.stopType.value === 'PICKUP' ? 'Người liên hệ lấy hàng' : 'Người nhận hàng' }}</label>
              <input [id]="'contactName' + i" formControlName="contactName" autocomplete="name" />
              @if (err(stop.controls.contactName); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field" [class.invalid]="err(stop.controls.contactPhone)">
              <label [for]="'contactPhone' + i">Số điện thoại</label><input [id]="'contactPhone' + i" type="tel" formControlName="contactPhone" placeholder="09xx xxx xxx" />
              @if (err(stop.controls.contactPhone); as e) { <span class="error">{{ e }}</span> }
            </div>
          </div>
          <app-address-search [stop]="stop" [index]="i" />
          <div class="grid-3">
            <div class="field" [class.invalid]="err(stop.controls.province)">
              <label [for]="'province' + i">Tỉnh/Thành phố</label><input [id]="'province' + i" formControlName="province" />
              @if (err(stop.controls.province); as e) { <span class="error">{{ e }}</span> }
            </div>
            <div class="field"><label [for]="'district' + i">Quận/Huyện</label><input [id]="'district' + i" formControlName="district" /></div>
            <div class="field"><label [for]="'ward' + i">Phường/Xã</label><input [id]="'ward' + i" formControlName="ward" /></div>
          </div>
          <div class="field" [class.invalid]="err(stop.controls.addressDetail)">
            <label [for]="'addressDetail' + i">Địa chỉ chi tiết</label><input [id]="'addressDetail' + i" formControlName="addressDetail" placeholder="Số nhà, tên đường, tòa nhà" />
            @if (err(stop.controls.addressDetail); as e) { <span class="error">{{ e }}</span> }
          </div>
          <details class="coords">
            <summary>Tọa độ GPS (không bắt buộc)</summary>
            <div class="grid-2">
              <div class="field" [class.invalid]="err(stop.controls.latitude) || stop.hasError('partialCoordinates')">
                <label [for]="'lat' + i">Vĩ độ</label><input [id]="'lat' + i" type="number" step="0.000001" formControlName="latitude" placeholder="21.0285" />
                @if (err(stop.controls.latitude); as e) { <span class="error">{{ e }}</span> }
                @else if (stop.hasError('partialCoordinates')) { <span class="error">Nhập đủ cả vĩ độ và kinh độ</span> }
              </div>
              <div class="field" [class.invalid]="err(stop.controls.longitude)">
                <label [for]="'lng' + i">Kinh độ</label><input [id]="'lng' + i" type="number" step="0.000001" formControlName="longitude" placeholder="105.8542" />
                @if (err(stop.controls.longitude); as e) { <span class="error">{{ e }}</span> }
              </div>
            </div>
            <p class="hint muted">Có thể bỏ trống. Hệ thống chỉ tự điền tọa độ khi dịch vụ bản đồ tìm được đúng địa chỉ, không bao giờ tự đoán.</p>
          </details>
          @if (stopServices(stop.controls.stopType.value); as defs) {
            @if (defs.length) {
              <fieldset class="stop-services" [attr.aria-label]="'Dịch vụ tại điểm ' + (i + 1)">
                <legend>Dịch vụ tại điểm này <span class="muted">(tính phí riêng cho từng điểm)</span></legend>
                @for (d of defs; track d.code) {
                  <div class="field">
                    <label [for]="'svc-' + i + '-' + d.code">{{ d.name }}</label>
                    @if (d.valueType === 'OPTION') {
                      <select [id]="'svc-' + i + '-' + d.code" [value]="stop.controls.services.value[d.code] ?? ''" (change)="setStopService(stop, d.code, $any($event.target).value)">
                        <option value="">Không cần</option>
                        @for (o of d.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                      </select>
                    } @else {
                      <input type="checkbox" [id]="'svc-' + i + '-' + d.code" [checked]="stop.controls.services.value[d.code] === 'true'"
                             (change)="setStopService(stop, d.code, $any($event.target).checked ? 'true' : '')" />
                    }
                  </div>
                }
                @if (stop.controls.services.errors?.['server']; as e) { <span class="error">{{ e }}</span> }
              </fieldset>
            }
          }
          <div class="field"><label [for]="'note' + i">Ghi chú cho tài xế</label><input [id]="'note' + i" formControlName="note" /></div>
        </section>
      }
    </div>
    <button class="btn btn-ghost" type="button" (click)="addDelivery()" [disabled]="form().controls.stops.length >= maxStops">+ Thêm điểm giao</button>
  </div>`,
  styles: [`.stop-head{display:flex;justify-content:space-between;align-items:center;gap:12px}.stop-head h2{margin:0 0 16px}.coords{margin-bottom:14px}.stop-services{border:1px dashed var(--tg-line);border-radius:6px;padding:8px 12px 0;margin:0 0 14px;display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:0 14px}.stop-services legend{font-size:13px;font-weight:600;padding:0 4px}.coords summary{cursor:pointer;font-size:13px;color:var(--tg-muted);margin-bottom:12px}`],
})
export class StopsStepComponent {
  readonly form = input.required<ShipmentForm>();
  /** Service catalog; services that may be ordered per stop are offered on each stop. */
  readonly catalog = input<ServiceDefinition[]>([]);

  stopServices(type: 'PICKUP' | 'DELIVERY'): ServiceDefinition[] {
    return this.catalog().filter(d => allowedAtStop(d, type) && (d.valueType === 'OPTION' || d.valueType === 'BOOLEAN'));
  }

  setStopService(stop: StopForm, code: string, value: string): void {
    const next = { ...stop.controls.services.value };
    if (value) {
      next[code] = value;
      this.form().controls.services.get(code)?.setValue(null);   // per stop replaces the order-wide choice (no double charge)
    } else delete next[code];
    stop.controls.services.setValue(next);
    stop.controls.services.markAsDirty();
  }
  protected readonly maxStops = 10;

  err(control: AbstractControl): string | null { return fieldError(control, MESSAGES); }

  stopsError(): string | null {
    const stops = this.form().controls.stops;
    if (!stops.touched && !stops.dirty) return null;
    if (stops.hasError('noPickup')) return 'Cần ít nhất một điểm lấy hàng';
    if (stops.hasError('noDelivery')) return 'Cần ít nhất một điểm giao hàng';
    if (stops.hasError('pickupFirst')) return 'Điểm đầu tiên phải là điểm lấy hàng';
    return null;
  }

  deliveryNumber(index: number): string {
    const deliveries = this.form().controls.stops.controls.filter(s => s.controls.stopType.value === 'DELIVERY');
    return deliveries.length > 1 ? `#${deliveries.indexOf(this.form().controls.stops.at(index)) + 1}` : '';
  }

  canRemove(index: number): boolean {
    const stops = this.form().controls.stops.controls;
    return stops[index].controls.stopType.value === 'DELIVERY' && stops.filter(s => s.controls.stopType.value === 'DELIVERY').length > 1;
  }

  addDelivery(): void { this.form().controls.stops.push(stopForm('DELIVERY')); this.form().controls.stops.markAsDirty(); }

  remove(index: number): void { this.form().controls.stops.removeAt(index); this.form().controls.stops.markAsDirty(); }
}
