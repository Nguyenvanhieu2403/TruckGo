import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { DriverTrip, DriverTripApi, MilestonePayload, NextAction, Photo, PhotoPurpose, STOP_STATUS_LABELS } from '../../core/api/trip.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { SelectedService } from '../../core/api/shipment.api';
import { GEOLOCATION, currentPosition } from '../../core/geo/geolocation';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { PhotoStripComponent } from '../../shared/photo-strip.component';
import { ToastService } from '../../shared/toast.service';
import { formatVnd } from '../../shared/money';
import { SHIPMENT_STATUS_LABELS, formatKg, formatM3 } from '../shipments/shipment-labels';
import { ShipmentStatusBadgeComponent } from '../shipments/shipment-status-badge.component';
import { GpsPanelComponent } from './gps-panel.component';
import { NEXT_ACTION_HINTS, NEXT_ACTION_LABELS, directionsLink, telLink } from './trip-labels';

const MILESTONE_PATHS: Partial<Record<NextAction, 'arrived' | 'pickup' | 'start' | 'deliver'>> = {
  ARRIVE_PICKUP: 'arrived', CONFIRM_PICKUP: 'pickup', START_TRIP: 'start', DELIVER: 'deliver',
};

/** Assigned driver's view of one trip with the single next action the trip allows. */
@Component({
  selector: 'app-driver-trip-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, ShipmentStatusBadgeComponent, ConfirmDialogComponent, PhotoStripComponent, GpsPanelComponent],
  template: `
  <main class="container">
    <a routerLink="/driver/trips" class="kicker">← Chuyến của tôi</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}</div> }
    @if (trip(); as t) {
      <div class="page-head"><div><h1>{{ t.shipmentCode }}</h1><span class="muted">Lấy hàng {{ t.pickupTime ? (t.pickupTime | date: 'HH:mm dd/MM/yyyy') : '—' }}</span></div>
        <div class="actions-row"><app-shipment-status [status]="t.status" />
          <a class="btn btn-ghost" [routerLink]="['/driver/trips', t.id, 'navigation']">Dẫn đường</a>
          <a class="btn btn-ghost" [routerLink]="['/driver/trips', t.id, 'incidents']">Sự cố@if (t.openIncidents) { ({{ t.openIncidents }}) }</a></div></div>
      @if (t.awaitingCustomerConfirmation) { <div class="alert alert-info">Đã giao hàng. Đang chờ khách hàng xác nhận bằng mã OTP.</div> }
      @if (t.status === 'COMPLETED') { <div class="alert alert-success">Chuyến đã hoàn thành lúc {{ t.milestones.completedAt | date: 'HH:mm dd/MM/yyyy' }}.</div> }

      <div class="detail-grid">
        <div>
          @if (t.nextAction !== 'NONE') {
            <section class="card next" aria-labelledby="next-title" data-testid="next-action">
              <h2 id="next-title">Bước tiếp theo</h2>
              <p class="muted">{{ hints[t.nextAction] }}</p>
              @if (nextStop(); as s) { <p><strong>Điểm {{ s.sequenceNo }}:</strong> {{ s.fullAddress }}</p> }
              @if (photoPurpose(); as purpose) {
                <div class="field"><label for="proof">Ảnh {{ purpose === 'PICKUP_PHOTO' ? 'nhận hàng' : 'giao hàng' }}@if (purpose === 'DELIVERY_PHOTO' && t.deliveryPhotoRequired) { (bắt buộc) } @else { (không bắt buộc) }</label>
                  <input id="proof" type="file" accept="image/jpeg,image/png,image/webp" (change)="upload($event, purpose)" [disabled]="uploading()" />
                  @if (uploading()) { <span class="muted small">Đang tải ảnh…</span> }</div>
                @if (pending().length) { <app-photo-strip [photos]="pending()" /> }
              }
              <div class="field"><label for="step-note">Ghi chú (không bắt buộc)</label><textarea id="step-note" rows="2" maxlength="500" [(ngModel)]="note"></textarea></div>
              <label class="checkbox"><input type="checkbox" [(ngModel)]="attachLocation" /> Đính kèm vị trí hiện tại (trình duyệt sẽ hỏi quyền vị trí)</label>
              <button class="btn btn-primary btn-block" type="button" (click)="confirmOpen.set(true)" [disabled]="busy() || uploading() || needsPhoto()">{{ labels[t.nextAction] }}</button>
              @if (needsPhoto()) { <p class="error small">Cần tải ít nhất một ảnh giao hàng.</p> }
            </section>
          }
          @if (t.status === 'IN_TRANSIT' || t.trackingSession) {
            <app-gps-panel [shipmentId]="t.id" [allowed]="t.trackingAllowed" [session]="t.trackingSession" [policy]="t.trackingPolicy" (changed)="reload()" />
          }
          <section class="card"><h2>Lộ trình</h2>
            <ol class="stops">
              @for (s of t.stops; track s.stopId) {
                <li [class.current]="s.current" [class.done]="s.status === 'COMPLETED'">
                  <div class="row"><span class="badge" [class.badge--approved]="s.status === 'COMPLETED'" [class.badge--pending_review]="s.status === 'ARRIVED'">{{ stopLabels[s.status] }}</span>
                    <strong>{{ s.sequenceNo }}. {{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</strong></div>
                  <div>{{ s.fullAddress }}</div>
                  <div class="muted small">{{ s.contactName }} · @if (tel(s.contactPhone); as href) { <a [href]="href">{{ s.contactPhone }}</a> }
                    @if (directions(s.fullAddress, s.latitude, s.longitude); as url) { · <a [href]="url" target="_blank" rel="noopener noreferrer">Chỉ đường</a> }</div>
                  @if (s.stopNote) { <em class="muted small">{{ s.stopNote }}</em> }
                  @if (s.arrivedAt) { <div class="muted small">Đến {{ s.arrivedAt | date: 'HH:mm dd/MM' }}@if (s.departedAt) { · rời {{ s.departedAt | date: 'HH:mm dd/MM' }} }</div> }
                </li>
              }
            </ol>
          </section>
          <section class="card"><h2>Hàng hóa</h2>
            <div class="table-wrap"><table class="table"><thead><tr><th>Tên</th><th>SL</th><th>Kg/kiện</th><th>Kích thước</th></tr></thead>
              <tbody>@for (i of t.items; track i.id) {
                <tr><td>{{ i.itemName }}<br><span class="muted">{{ i.categoryName }}</span></td><td>{{ i.quantity }}</td><td>{{ i.weightPerUnitKg | number: '1.0-2' }}</td>
                  <td>{{ i.lengthCm ? i.lengthCm + '×' + i.widthCm + '×' + i.heightCm + ' cm' : '—' }}</td></tr>
              }</tbody></table></div>
            <p class="muted">Tổng {{ t.totalQuantity }} kiện · {{ kg(t.totalWeightKg) }} · {{ m3(t.totalVolumeM3) }}</p>
            @for (x of t.services; track x.serviceCode) { <p class="svc">✓ {{ serviceText(x) }}</p> }
            @if (t.customerNote) { <p><strong>Ghi chú của khách:</strong> {{ t.customerNote }}</p> }
          </section>
        </div>
        <aside>
          <section class="card"><h2>Thông tin</h2>
            <dl class="dl compact">
              <dt>Khách hàng</dt><dd>{{ t.customerName || '—' }}@if (tel(t.customerPhone); as href) { <br><a [href]="href">{{ t.customerPhone }}</a> }</dd>
              @if (t.vehicle; as v) { <dt>Xe</dt><dd>{{ v.plateNumber }} · {{ vehicleLabels[v.vehicleType] }}</dd> }
              <dt>Giá cước</dt><dd>{{ vnd(t.quote?.totalPrice) }}</dd>
              @if (t.quote?.distanceKm) { <dt>Quãng đường</dt><dd>{{ t.quote?.distanceKm | number: '1.0-1' }} km</dd> }
              <dt>Giao trước</dt><dd>{{ t.deliveryDeadline ? (t.deliveryDeadline | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
            </dl>
          </section>
          <section class="card"><h2>Ảnh của chuyến</h2><app-photo-strip [photos]="t.photos" /></section>
          <section class="card"><h2>Hành trình</h2>
            <ol class="timeline">@for (h of t.statusHistory; track h.createdAt) {
              <li><strong>{{ statusLabels[h.newStatus] }}</strong><span class="muted">{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span>@if (h.changeReason) { <em>{{ h.changeReason }}</em> }</li>
            }</ol>
          </section>
        </aside>
      </div>
      @if (confirmOpen()) {
        <app-confirm-dialog [title]="labels[t.nextAction] + '?'" [message]="hints[t.nextAction]" [confirmLabel]="labels[t.nextAction]" [busy]="busy()" [error]="dialogError()"
                            (confirmed)="act()" (closed)="confirmOpen.set(false)" />
      }
    } @else if (!error()) { <div class="card" aria-busy="true" style="margin-top:20px">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div> }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:20px}.next{border-color:#8eb85a}.small{font-size:12px}
  .stops{list-style:none;padding:0;margin:0}.stops li{padding:10px 0 10px 12px;border-left:3px solid #e2e7dc;margin-bottom:6px;font-size:14px;display:flex;flex-direction:column;gap:2px}
  .stops li.current{border-left-color:#2f6fc4;background:#f4f8fd}.stops li.done{border-left-color:#78a648}.row{display:flex;gap:8px;align-items:center}
  .dl.compact{grid-template-columns:100px minmax(0,1fr)}.svc{margin:4px 0;font-size:14px}.checkbox{display:flex;gap:8px;align-items:center;font-size:13px;margin:6px 0 12px}
  .timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;flex-direction:column;gap:2px;padding:8px 0 8px 14px;border-left:2px solid #cfe0bd;font-size:13px}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class DriverTripDetailComponent implements OnInit {
  private readonly api = inject(DriverTripApi);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly geo = inject(GEOLOCATION);
  protected readonly trip = signal<DriverTrip | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly confirmOpen = signal(false);
  /** Photos uploaded for the pending step, sent with it. */
  protected readonly pending = signal<Photo[]>([]);
  protected note = '';
  protected attachLocation = false;
  protected readonly labels = NEXT_ACTION_LABELS;
  protected readonly hints = NEXT_ACTION_HINTS;
  protected readonly stopLabels = STOP_STATUS_LABELS;
  protected readonly statusLabels = SHIPMENT_STATUS_LABELS;
  protected readonly vehicleLabels = VEHICLE_TYPE_LABELS;
  protected readonly vnd = formatVnd;
  protected readonly kg = formatKg;
  protected readonly m3 = formatM3;
  protected readonly tel = telLink;
  protected readonly directions = directionsLink;
  protected readonly nextStop = computed(() => {
    const t = this.trip();
    return t?.nextStopId ? t.stops.find(s => s.stopId === t.nextStopId) ?? null : null;
  });
  protected readonly photoPurpose = computed<PhotoPurpose | null>(() => {
    const a = this.trip()?.nextAction;
    return a === 'CONFIRM_PICKUP' ? 'PICKUP_PHOTO' : a === 'DELIVER' ? 'DELIVERY_PHOTO' : null;
  });
  protected readonly needsPhoto = computed(() => this.trip()?.nextAction === 'DELIVER' && !!this.trip()?.deliveryPhotoRequired && this.pending().length === 0);

  private get id(): string { return this.route.snapshot.paramMap.get('shipmentId') ?? ''; }

  ngOnInit(): void { this.reload(); }

  reload(): void {
    this.api.get(this.id).subscribe({
      next: t => this.trip.set(t),
      error: (e: unknown) => this.error.set(toApiError(e).status === 404 ? 'Không tìm thấy chuyến hoặc chuyến không được giao cho bạn.' : toApiError(e).message),
    });
  }

  upload(event: Event, purpose: PhotoPurpose): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploading.set(true);
    this.api.upload(this.id, purpose, file).subscribe({
      next: photo => { this.uploading.set(false); this.pending.update(list => [...list, photo]); input.value = ''; },
      error: (e: unknown) => { this.uploading.set(false); this.toast.error(toApiError(e).message); input.value = ''; },
    });
  }

  async act(): Promise<void> {
    const t = this.trip();
    if (!t || this.busy()) return;
    this.busy.set(true);
    this.dialogError.set(null);
    const body: MilestonePayload = { note: this.note.trim() || null, attachments: this.pending().map(p => p.id) };
    if (this.attachLocation) {
      const p = await currentPosition(this.geo);
      if (p) { body.latitude = Math.round(p.coords.latitude * 1e6) / 1e6; body.longitude = Math.round(p.coords.longitude * 1e6) / 1e6; }
    }
    const label = NEXT_ACTION_LABELS[t.nextAction];
    const request = t.nextAction === 'ARRIVE_STOP' || t.nextAction === 'COMPLETE_STOP'
      ? this.api.stop(t.id, t.nextStopId!, t.nextAction === 'ARRIVE_STOP' ? 'arrive' : 'complete', body)
      : this.api.act(t.id, MILESTONE_PATHS[t.nextAction]!, body);
    request.subscribe({
      next: updated => {
        this.busy.set(false);
        this.confirmOpen.set(false);
        this.trip.set(updated);
        this.pending.set([]);
        this.note = '';
        this.toast.success(`Đã cập nhật: ${label}.`);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.dialogError.set(err.message);
        if (err.code === 'INVALID_TRIP_STATUS' || err.code === 'STOP_OUT_OF_ORDER') this.reload();
      },
    });
  }

  serviceText(x: SelectedService): string {
    const detail = x.valueLabel ?? (x.valueType === 'NUMBER' || x.valueType === 'TEXT' ? x.serviceValue : null);
    return detail ? `${x.serviceName}: ${detail}` : x.serviceName;
  }
}
