import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { APPLICATION_STATUS_LABELS, DriverMarketplaceApi, MarketplaceShipmentDetail, VehicleOption } from '../../core/api/marketplace.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { SelectedService } from '../../core/api/shipment.api';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { ToastService } from '../../shared/toast.service';
import { formatVnd } from '../../shared/money';
import { formatKg, formatM3 } from '../shipments/shipment-labels';
import { StopListComponent } from './stop-list.component';
import { MARKETPLACE_ERROR_HINTS, applicationBadge, formatKm } from './marketplace-labels';

@Component({
  selector: 'app-driver-marketplace-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ReactiveFormsModule, RouterLink, StopListComponent, ConfirmDialogComponent],
  template: `
  <main class="container">
    <a routerLink="/driver/marketplace" class="kicker">← Tìm đơn</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}
      @if (!shipment()) { <a class="btn btn-ghost" routerLink="/driver/marketplace">Về danh sách</a> }</div> }
    @if (shipment(); as s) {
      <div class="page-head"><div><h1>{{ s.shipmentCode }}</h1><span class="muted">Lấy hàng {{ s.pickupTime | date: 'HH:mm dd/MM/yyyy' }}</span></div>
        <div class="price">{{ vnd(s.price) }}</div></div>
      <div class="detail-grid">
        <div>
          <section class="card"><h2>Lộ trình</h2><app-stop-list [stops]="s.stops" [revealed]="s.assignedToMe" />
            <p class="muted">Quãng đường ước tính: {{ km(s.distanceKm) }}</p></section>
          <section class="card"><h2>Hàng hóa</h2>
            <div class="table-wrap"><table class="table"><thead><tr><th>Tên</th><th>SL</th><th>Kg/kiện</th><th>Kích thước</th><th>Tổng kg</th></tr></thead>
              <tbody>@for (i of s.items; track i.id) {
                <tr><td>{{ i.itemName }}<br><span class="muted">{{ i.categoryName }}</span></td><td>{{ i.quantity }}</td><td>{{ i.weightPerUnitKg | number: '1.0-2' }}</td>
                  <td>{{ i.lengthCm ? i.lengthCm + '×' + i.widthCm + '×' + i.heightCm + ' cm' : '—' }}</td><td>{{ i.totalWeightKg | number: '1.0-2' }}</td></tr>
              }</tbody></table></div>
          </section>
        </div>
        <aside>
          <section class="card apply" aria-labelledby="apply-title"><h2 id="apply-title">Nhận đơn</h2>
            @if (s.myApplication; as mine) {
              <p>Yêu cầu của bạn: <span [class]="badge(mine.status)">{{ statusLabels[mine.status] }}</span></p>
              <p class="muted">Gửi lúc {{ mine.appliedAt | date: 'HH:mm dd/MM/yyyy' }} · Xe {{ mine.plateNumber }}</p>
              @if (mine.responseReason) { <p class="muted">Phản hồi: {{ mine.responseReason }}</p> }
              <a class="btn btn-ghost" [routerLink]="['/driver/applications', mine.id]">Xem yêu cầu</a>
            }
            @if (s.canApply) {
              <form [formGroup]="form" (ngSubmit)="askConfirm()">
                <fieldset class="vehicles"><legend>Chọn xe</legend>
                  @for (v of s.vehicles; track v.id) {
                    <label class="vehicle" [class.disabled]="!v.eligible">
                      <input type="radio" formControlName="vehicleId" [value]="v.id" [attr.disabled]="v.eligible ? null : true" />
                      <span><strong>{{ v.plateNumber }}</strong> · {{ vehicleLabel(v) }} · {{ v.maxPayloadKg | number }} kg
                        @if (!v.eligible) { <br><span class="error">{{ v.reasons.join('; ') }}</span> }</span></label>
                  }
                </fieldset>
                <div class="field"><label for="driver-message">Lời nhắn cho khách (không bắt buộc)</label>
                  <textarea id="driver-message" rows="3" maxlength="500" formControlName="driverMessage" placeholder="VD: Xe có bạt che, có thể đến sớm 30 phút"></textarea></div>
                <button class="btn btn-primary btn-block" type="submit" [disabled]="form.invalid || busy()">Gửi yêu cầu nhận đơn</button>
              </form>
            } @else if (s.unavailableReason) {
              <div class="alert alert-warn">{{ s.unavailableReason }}</div>
              @if (!s.driverApproved) { <a class="btn btn-ghost" routerLink="/driver/verification">Xem trạng thái duyệt</a> }
              @else if (!hasEligibleVehicle()) { <a class="btn btn-ghost" routerLink="/driver/vehicles">Quản lý phương tiện</a> }
            }
            @if (s.applicationDeadline && s.canApply) { <p class="muted small">Hạn gửi yêu cầu: {{ s.applicationDeadline | date: 'HH:mm dd/MM/yyyy' }}</p> }
          </section>
          <section class="card"><h2>Yêu cầu xe</h2>
            <dl class="dl compact">
              <dt>Loại xe</dt><dd>{{ s.vehicle.vehicleTypeName || 'Không yêu cầu' }}</dd>
              <dt>Loại thùng</dt><dd>{{ s.vehicle.bodyTypeName || 'Không yêu cầu' }}</dd>
              <dt>Tổng kiện</dt><dd>{{ s.totalQuantity }}</dd><dt>Trọng lượng</dt><dd>{{ kg(s.totalWeightKg) }}</dd><dt>Thể tích</dt><dd>{{ m3(s.totalVolumeM3) }}</dd>
              <dt>Giao trước</dt><dd>{{ s.deliveryDeadline ? (s.deliveryDeadline | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
            </dl>
          </section>
          <section class="card"><h2>Dịch vụ bổ sung</h2>
            @for (x of s.services; track x.serviceCode) { <p class="svc">✓ {{ serviceText(x) }}</p> } @empty { <p class="muted">Không có.</p> }
            @if (s.customerNote) { <p><strong>Ghi chú của khách:</strong> {{ s.customerNote }}</p> }
          </section>
        </aside>
      </div>
      @if (confirmOpen()) {
        <app-confirm-dialog title="Gửi yêu cầu nhận đơn?" [message]="confirmText()" confirmLabel="Gửi yêu cầu" [busy]="busy()" [error]="dialogError()"
                            (confirmed)="apply()" (closed)="confirmOpen.set(false)" />
      }
    } @else if (!error()) {
      <div class="card" aria-busy="true" style="margin-top:20px">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div>
    }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:20px}.price{font-size:24px;font-weight:800;color:var(--tg-dark)}
  .vehicles{border:0;padding:0;margin:0 0 12px}.vehicles legend{font-weight:600;font-size:13px;margin-bottom:6px}
  .vehicle{display:flex;gap:10px;align-items:flex-start;padding:10px;border:1px solid var(--tg-line);border-radius:6px;margin-bottom:8px;font-size:14px;cursor:pointer}
  .vehicle.disabled{opacity:.7;cursor:not-allowed}.vehicle .error{font-size:12px}.dl.compact{grid-template-columns:110px 1fr}.svc{margin:4px 0;font-size:14px}.small{font-size:12px}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}aside{order:-1}}`],
})
export class DriverMarketplaceDetailComponent implements OnInit {
  private readonly api = inject(DriverMarketplaceApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly shipment = signal<MarketplaceShipmentDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirmOpen = signal(false);
  protected readonly statusLabels = APPLICATION_STATUS_LABELS;
  protected readonly form = inject(FormBuilder).nonNullable.group({ vehicleId: ['', Validators.required], driverMessage: ['', Validators.maxLength(500)] });
  protected readonly hasEligibleVehicle = computed(() => (this.shipment()?.vehicles ?? []).some(v => v.eligible));
  protected readonly vnd = formatVnd;
  protected readonly kg = formatKg;
  protected readonly m3 = formatM3;
  protected readonly km = formatKm;
  protected readonly badge = applicationBadge;

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.detail(this.route.snapshot.paramMap.get('shipmentId') ?? '').subscribe({
      next: s => {
        this.shipment.set(s);
        const eligible = s.vehicles.filter(v => v.eligible);
        if (eligible.length === 1) this.form.controls.vehicleId.setValue(eligible[0].id);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.error.set(err.status === 404 ? 'Đơn không tồn tại hoặc không còn nhận tài xế.' : err.message);
      },
    });
  }

  vehicleLabel(v: VehicleOption): string { return VEHICLE_TYPE_LABELS[v.vehicleType] ?? v.vehicleType; }

  confirmText(): string {
    const s = this.shipment();
    const v = s?.vehicles.find(x => x.id === this.form.controls.vehicleId.value);
    return `Bạn sẽ gửi yêu cầu nhận đơn ${s?.shipmentCode} với xe ${v?.plateNumber ?? ''}. Khách hàng sẽ nhận được thông báo và chọn một tài xế.`;
  }

  askConfirm(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.dialogError.set(null);
    this.confirmOpen.set(true);
  }

  apply(): void {
    const s = this.shipment();
    if (!s || this.busy()) return;
    const { vehicleId, driverMessage } = this.form.getRawValue();
    this.busy.set(true);
    this.api.apply(s.id, vehicleId, driverMessage.trim() || null).subscribe({
      next: application => {
        this.busy.set(false);
        this.confirmOpen.set(false);
        this.toast.success(`Đã gửi yêu cầu nhận đơn ${s.shipmentCode}.`);
        void this.router.navigate(['/driver/applications', application.id]);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        const hint = err.code ? MARKETPLACE_ERROR_HINTS[err.code] : undefined;
        this.dialogError.set(hint ?? err.message);
        if (err.code === 'SHIPMENT_ALREADY_ASSIGNED' || err.code === 'SHIPMENT_NOT_AVAILABLE') this.load();
      },
    });
  }

  serviceText(x: SelectedService): string {
    const detail = x.valueLabel ?? (x.valueType === 'NUMBER' || x.valueType === 'TEXT' ? x.serviceValue : null);
    return detail ? `${x.serviceName}: ${detail}` : x.serviceName;
  }
}
