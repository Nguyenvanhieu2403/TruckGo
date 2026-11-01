import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { APPLICATION_STATUS_LABELS, DriverApplication, DriverMarketplaceApi, areaText } from '../../core/api/marketplace.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { ToastService } from '../../shared/toast.service';
import { formatVnd } from '../../shared/money';
import { SHIPMENT_STATUS_LABELS, formatKg } from '../shipments/shipment-labels';
import { StopListComponent } from './stop-list.component';
import { applicationBadge, formatKm } from './marketplace-labels';

@Component({
  selector: 'app-driver-application-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink, StopListComponent, ConfirmDialogComponent],
  template: `
  <main class="container">
    <a routerLink="/driver/applications" class="kicker">← Yêu cầu của tôi</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}</div> }
    @if (application(); as a) {
      <div class="page-head"><div><h1>Đơn {{ a.shipment?.shipmentCode }}</h1><span class="muted">Gửi yêu cầu lúc {{ a.appliedAt | date: 'HH:mm dd/MM/yyyy' }}</span></div>
        <div class="actions-row"><span [class]="badge(a.status)" data-testid="application-status">{{ labels[a.status] }}</span>
          @if (a.withdrawable) { <button class="btn btn-ghost" type="button" (click)="openWithdraw()">Rút yêu cầu</button> }</div></div>
      @if (a.status === 'ACCEPTED') { <div class="alert alert-success">Khách hàng đã chọn bạn cho đơn này. Liên hệ người gửi theo thông tin bên dưới.</div> }
      @if (a.status === 'RELEASED') { <div class="alert alert-warn" data-testid="released">Bạn đã được gỡ khỏi đơn này trước khi chuyến bắt đầu. Địa chỉ và liên hệ không còn hiển thị.</div> }
      @if (a.status === 'PENDING') { <div class="alert alert-info">Đang chờ khách hàng chọn tài xế. Bạn sẽ nhận được thông báo khi có kết quả.</div> }
      @if (a.responseReason) { <div class="alert alert-warn">{{ a.status === 'WITHDRAWN' ? 'Lý do rút' : a.status === 'RELEASED' ? 'Lý do gỡ' : 'Phản hồi' }}: {{ a.responseReason }}</div> }
      <div class="detail-grid">
        <section class="card"><h2>Lộ trình</h2><app-stop-list [stops]="a.stops" [revealed]="a.status === 'ACCEPTED'" /></section>
        <aside>
          <section class="card"><h2>Thông tin</h2>
            <dl class="dl compact">
              @if (a.shipment; as s) {
                <dt>Trạng thái đơn</dt><dd>{{ shipmentLabels[s.status] }}</dd>
                <dt>Lấy hàng</dt><dd>{{ s.pickupTime ? (s.pickupTime | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
                <dt>Tuyến</dt><dd>{{ area(s.pickup) }} → {{ area(s.delivery) }}</dd>
                <dt>Giá cước</dt><dd>{{ vnd(s.price) }}</dd><dt>Quãng đường</dt><dd>{{ km(s.distanceKm) }}</dd><dt>Khối lượng</dt><dd>{{ kg(s.totalWeightKg) }}</dd>
              }
              @if (a.vehicle; as v) { <dt>Xe</dt><dd>{{ v.plateNumber }} · {{ vehicleLabels[v.vehicleType] }} · {{ v.maxPayloadKg | number }} kg</dd> }
              <dt>Lời nhắn</dt><dd>{{ a.driverMessage || '—' }}</dd>
              @if (a.respondedAt) { <dt>Phản hồi lúc</dt><dd>{{ a.respondedAt | date: 'HH:mm dd/MM/yyyy' }}</dd> }
            </dl>
            @if (a.status === 'PENDING' && a.shipment) { <a class="btn btn-ghost" [routerLink]="['/driver/marketplace', a.shipment.id]">Xem đơn</a> }
          </section>
        </aside>
      </div>
      @if (withdrawOpen()) {
        <app-confirm-dialog title="Rút yêu cầu nhận đơn?" message="Khách hàng sẽ không còn thấy yêu cầu này. Bạn có thể gửi lại nếu đơn vẫn mở."
                            confirmLabel="Rút yêu cầu" [danger]="true" reasonLabel="Lý do (không bắt buộc)" [busy]="busy()" [error]="dialogError()"
                            (confirmed)="withdraw($event)" (closed)="withdrawOpen.set(false)" />
      }
    } @else if (!error()) { <div class="card" aria-busy="true" style="margin-top:20px">@for (n of [1, 2]; track n) { <div class="skeleton-row"></div> }</div> }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr);gap:20px}.dl.compact{grid-template-columns:110px minmax(0,1fr)}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class DriverApplicationDetailComponent implements OnInit {
  private readonly api = inject(DriverMarketplaceApi);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  protected readonly application = signal<DriverApplication | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly withdrawOpen = signal(false);
  protected readonly labels = APPLICATION_STATUS_LABELS;
  protected readonly shipmentLabels = SHIPMENT_STATUS_LABELS;
  protected readonly vehicleLabels = VEHICLE_TYPE_LABELS;
  protected readonly area = areaText;
  protected readonly vnd = formatVnd;
  protected readonly km = formatKm;
  protected readonly kg = formatKg;
  protected readonly badge = applicationBadge;

  ngOnInit(): void {
    this.api.application(this.route.snapshot.paramMap.get('applicationId') ?? '').subscribe({
      next: a => this.application.set(a),
      error: (e: unknown) => this.error.set(toApiError(e).status === 404 ? 'Không tìm thấy yêu cầu nhận đơn.' : toApiError(e).message),
    });
  }

  openWithdraw(): void { this.dialogError.set(null); this.withdrawOpen.set(true); }

  withdraw(reason: string | null): void {
    const a = this.application();
    if (!a || this.busy()) return;
    this.busy.set(true);
    this.api.withdraw(a.id, reason).subscribe({
      next: updated => { this.busy.set(false); this.withdrawOpen.set(false); this.application.set(updated); this.toast.success('Đã rút yêu cầu nhận đơn.'); },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.dialogError.set(err.code === 'APPLICATION_NOT_PENDING' ? 'Yêu cầu đã được khách hàng xử lý, không thể rút nữa.' : err.message);
      },
    });
  }
}
