import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, interval } from 'rxjs';
import { toApiError } from '../../core/api.service';
import { CustomerTrip, CustomerTripApi, Incident, STOP_STATUS_LABELS, TRACKING_STATE_LABELS, Tracking } from '../../core/api/trip.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { StreamStatus, TripStreamClient } from '../../core/trip-stream';
import { TripMapComponent } from '../../shared/map/trip-map.component';
import { PhotoStripComponent } from '../../shared/photo-strip.component';
import { ToastService } from '../../shared/toast.service';
import { SHIPMENT_STATUS_LABELS } from '../shipments/shipment-labels';
import { ShipmentStatusBadgeComponent } from '../shipments/shipment-status-badge.component';
import { IncidentListComponent } from './incident-list.component';
import { telLink, trackingBadge } from './trip-labels';

const POLL_MS = 15_000;
const ACTIVE = new Set(['ASSIGNED', 'DRIVER_ARRIVED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED']);

/**
 * Customer view of a running trip. Live updates arrive over the authenticated event stream; while it is not open the
 * page polls. Positions are shown with their age and state, never extrapolated, and not at all once the trip ended.
 */
@Component({
  selector: 'app-customer-tracking',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, ShipmentStatusBadgeComponent, TripMapComponent, PhotoStripComponent, IncidentListComponent],
  template: `
  <main class="container">
    <a [routerLink]="['/customer/shipments', id]" class="kicker">← Chi tiết đơn</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}</div> }
    @if (trip(); as t) {
      <div class="page-head"><div><h1>Theo dõi {{ t.shipmentCode }}</h1>
        <span class="muted small" data-testid="stream-status">{{ streamStatus() === 'open' ? 'Đang nhận cập nhật trực tiếp' : 'Tự làm mới mỗi 15 giây' }}</span></div>
        <app-shipment-status [status]="t.status" /></div>

      @if (t.status === 'DELIVERED' && t.confirmation; as c) {
        <section class="card confirm" aria-labelledby="otp-title" data-testid="confirm-delivery">
          <h2 id="otp-title">Xác nhận đã nhận hàng</h2>
          <p class="muted">Tài xế đã báo giao hàng. Kiểm tra hàng rồi nhập mã 6 số đã gửi cho bạn ({{ channelText(c.channel) }}). Mã hiệu lực đến {{ c.expiresAt | date: 'HH:mm dd/MM/yyyy' }}.</p>
          @if (c.locked) { <div class="alert alert-warn">Bạn đã nhập sai quá số lần cho phép. Hãy yêu cầu mã mới.</div> }
          @else if (!c.pending) { <div class="alert alert-warn">Mã đã hết hạn. Hãy yêu cầu mã mới.</div> }
          @if (otpError()) { <div class="alert alert-error" role="alert">{{ otpError() }}</div> }
          <div class="otp-row">
            <div class="field"><label for="otp">Mã xác nhận</label>
              <input id="otp" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [(ngModel)]="otp" [disabled]="busy() || !c.pending" /></div>
            <div class="field grow"><label for="otp-note">Ghi chú (không bắt buộc)</label><input id="otp-note" maxlength="500" [(ngModel)]="otpNote" [disabled]="busy()" /></div>
          </div>
          <div class="actions-row">
            <button class="btn btn-primary" type="button" (click)="confirm()" [disabled]="busy() || !c.pending || !validOtp()">Xác nhận đã nhận hàng</button>
            <button class="btn btn-ghost" type="button" (click)="resend()" [disabled]="busy() || !canResend()">{{ resendLabel() }}</button>
          </div>
          @if (c.pending) { <p class="muted small">Còn {{ c.attemptsLeft }} lần nhập.</p> }
        </section>
      }

      <div class="detail-grid">
        <div>
          <section class="card" aria-labelledby="loc-title"><h2 id="loc-title">Vị trí tài xế</h2>
            @if (tracking(); as tr) {
              <p><span [class]="badge(tr.state)" data-testid="tracking-state">{{ stateLabels[tr.state] }}</span>
                @if (tr.recordedAt) { <span class="muted small"> · cập nhật {{ tr.recordedAt | date: 'HH:mm:ss dd/MM' }} ({{ ageText(tr) }})</span> }</p>
              @switch (tr.state) {
                @case ('STALE') { <div class="alert alert-warn" role="status">Vị trí này đã cũ, tài xế có thể đã di chuyển. Đang chờ cập nhật mới.</div> }
                @case ('OFFLINE') { <div class="alert alert-warn" role="status">Không nhận được vị trí mới từ tài xế. Vị trí bên dưới (nếu có) là nơi tài xế được ghi nhận lần cuối.</div> }
                @case ('PAUSED') { <div class="alert alert-info" role="status">Tài xế đang tạm dừng chia sẻ vị trí.</div> }
                @case ('NOT_STARTED') { <p class="muted">Vị trí sẽ hiển thị khi tài xế bắt đầu vận chuyển và chia sẻ vị trí.</p> }
                @case ('ENDED') { <p class="muted">Chuyến đã kết thúc theo dõi vị trí.</p> }
              }
              @if (tr.speed !== null && tr.state === 'LIVE') { <p class="muted small">Tốc độ {{ tr.speed | number: '1.0-0' }} km/h@if (tr.accuracy !== null) { · sai số ±{{ tr.accuracy | number: '1.0-0' }} m }</p> }
            }
            <app-trip-map [tracking]="tracking()" [stops]="t.stops" />
          </section>
          <section class="card"><h2>Điểm dừng</h2>
            <ol class="stops">@for (s of t.stops; track s.stopId) {
              <li [class.current]="s.current" [class.done]="s.status === 'COMPLETED'"><strong>{{ s.sequenceNo }}. {{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</strong> · {{ stopLabels[s.status] }}
                <div>{{ s.fullAddress }}</div>
                @if (s.arrivedAt) { <div class="muted small">Đến {{ s.arrivedAt | date: 'HH:mm dd/MM' }}@if (s.departedAt) { · xong {{ s.departedAt | date: 'HH:mm dd/MM' }} }</div> }
                @if (s.progressNote) { <em class="muted small">{{ s.progressNote }}</em> }</li>
            }</ol>
          </section>
          <section class="card"><h2>Sự cố</h2><app-incident-list [incidents]="incidents()" /></section>
        </div>
        <aside>
          <section class="card"><h2>Tài xế và xe</h2>
            <dl class="dl compact">
              <dt>Tài xế</dt><dd>{{ t.driver?.fullName || '—' }}@if (tel(t.driver?.phone ?? null); as href) { <br><a [href]="href">{{ t.driver?.phone }}</a> }</dd>
              @if (t.vehicle; as v) { <dt>Xe</dt><dd>{{ v.plateNumber }} · {{ vehicleLabels[v.vehicleType] }}</dd> }
              <dt>Dự kiến đến</dt><dd>{{ t.estimatedArrival ? (t.estimatedArrival | date: 'HH:mm dd/MM') : 'Chưa có dữ liệu tin cậy' }}</dd>
            </dl>
          </section>
          <section class="card"><h2>Mốc vận chuyển</h2>
            <ol class="timeline">@for (h of t.statusHistory; track h.createdAt) {
              <li><strong>{{ statusLabels[h.newStatus] }}</strong><span class="muted">{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span>@if (h.changeReason) { <em>{{ h.changeReason }}</em> }</li>
            }</ol>
          </section>
          <section class="card"><h2>Ảnh nhận/giao hàng</h2><app-photo-strip [photos]="t.photos" /></section>
        </aside>
      </div>
    } @else if (!error()) { <div class="card" aria-busy="true" style="margin-top:20px">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div> }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:20px}.small{font-size:12px}.confirm{border-color:#8eb85a}
  .otp-row{display:flex;gap:12px;flex-wrap:wrap}.otp-row #otp{width:9em;letter-spacing:4px;font-size:18px}.grow{flex:1;min-width:200px}
  .stops{list-style:none;padding:0;margin:0}.stops li{padding:8px 0 8px 12px;border-left:3px solid #e2e7dc;margin-bottom:6px;font-size:14px}
  .stops li.current{border-left-color:#2f6fc4;background:#f4f8fd}.stops li.done{border-left-color:#78a648}.dl.compact{grid-template-columns:100px minmax(0,1fr)}
  .timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;flex-direction:column;gap:2px;padding:8px 0 8px 14px;border-left:2px solid #cfe0bd;font-size:13px}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class CustomerTrackingComponent implements OnInit {
  private readonly api = inject(CustomerTripApi);
  private readonly stream = inject(TripStreamClient);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly id = inject(ActivatedRoute).snapshot.paramMap.get('shipmentId') ?? '';
  protected readonly trip = signal<CustomerTrip | null>(null);
  protected readonly tracking = signal<Tracking | null>(null);
  protected readonly incidents = signal<Incident[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly otpError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly streamStatus = signal<StreamStatus>('closed');
  private readonly now = signal(Date.now());
  protected otp = '';
  protected otpNote = '';
  protected readonly stateLabels = TRACKING_STATE_LABELS;
  protected readonly stopLabels = STOP_STATUS_LABELS;
  protected readonly statusLabels = SHIPMENT_STATUS_LABELS;
  protected readonly vehicleLabels = VEHICLE_TYPE_LABELS;
  protected readonly badge = trackingBadge;
  protected readonly tel = telLink;
  protected readonly canResend = computed(() => {
    const at = this.trip()?.confirmation?.resendAvailableAt;
    return !!at && new Date(at).getTime() <= this.now();
  });
  protected readonly resendLabel = computed(() => {
    const at = this.trip()?.confirmation?.resendAvailableAt;
    if (!at) return 'Không thể gửi lại mã';
    const wait = Math.ceil((new Date(at).getTime() - this.now()) / 1000);
    return wait > 0 ? `Gửi lại mã sau ${wait}s` : 'Gửi lại mã';
  });
  private live?: Subscription;

  ngOnInit(): void {
    this.load();
    const tick = interval(1000).subscribe(() => this.now.set(Date.now()));
    // Fallback polling whenever the live stream is not open.
    const poll = interval(POLL_MS).subscribe(() => { if (this.streamStatus() !== 'open' && this.isActive()) this.refreshTracking(); });
    this.destroyRef.onDestroy(() => { tick.unsubscribe(); poll.unsubscribe(); this.live?.unsubscribe(); });
  }

  validOtp(): boolean { return /^\d{6}$/.test(this.otp.trim()); }

  channelText(channel: string): string { return channel === 'in-app' ? 'trong mục Thông báo' : channel; }

  ageText(t: Tracking): string {
    const s = t.ageSeconds ?? 0;
    return s < 60 ? `${s} giây trước` : s < 3600 ? `${Math.floor(s / 60)} phút trước` : `${Math.floor(s / 3600)} giờ trước`;
  }

  load(): void {
    this.api.trip(this.id).subscribe({
      next: t => {
        this.trip.set(t);
        this.tracking.set(t.tracking);
        if (this.isActive() && !this.live) this.connect();
        if (!this.isActive()) { this.live?.unsubscribe(); this.live = undefined; }
      },
      error: (e: unknown) => this.error.set(toApiError(e).status === 404 ? 'Đơn chưa có tài xế hoặc không thuộc về bạn.' : toApiError(e).message),
    });
    this.api.incidents(this.id).subscribe({ next: list => this.incidents.set(list), error: () => undefined });
  }

  confirm(): void {
    if (!this.validOtp() || this.busy()) return;
    this.busy.set(true);
    this.otpError.set(null);
    this.api.confirm(this.id, this.otp.trim(), this.otpNote.trim() || null).subscribe({
      next: t => { this.busy.set(false); this.trip.set(t); this.tracking.set(t.tracking); this.otp = ''; this.toast.success('Đã xác nhận nhận hàng. Chuyến đã hoàn thành.'); },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.otp = '';
        this.otpError.set(err.message);
        this.load();   // attempts left / locked state come from the server
      },
    });
  }

  resend(): void {
    this.busy.set(true);
    this.otpError.set(null);
    this.api.resend(this.id).subscribe({
      next: c => { this.busy.set(false); this.trip.update(t => (t ? { ...t, confirmation: c } : t)); this.toast.success('Đã gửi mã mới.'); },
      error: (e: unknown) => { this.busy.set(false); this.otpError.set(toApiError(e).message); },
    });
  }

  private isActive(): boolean { return ACTIVE.has(this.trip()?.status ?? ''); }

  private refreshTracking(): void {
    this.api.tracking(this.id).subscribe({ next: t => this.tracking.set(t), error: () => undefined });
  }

  private connect(): void {
    this.live = this.stream.connect(this.api.streamUrl(this.id), s => this.streamStatus.set(s)).subscribe(event => {
      if (event.type === 'location') this.tracking.set(event.data as Tracking);
      else if ((event.data as { status?: string }).status !== this.trip()?.status) this.load();
    });
  }
}
