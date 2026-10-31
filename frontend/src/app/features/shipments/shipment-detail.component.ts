import { Component, OnInit, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { SelectedService, ShipmentApi, ShipmentDetail } from '../../core/api/shipment.api';
import { toApiError } from '../../core/api.service';
import { AttachmentPanelComponent } from './attachment-panel.component';
import { QuotePanelComponent } from '../pricing/quote-panel.component';
import { ApplicantsPanelComponent } from '../marketplace/applicants-panel.component';
import { formatVnd } from '../../shared/money';
import { CancelDialogComponent } from './cancel-dialog.component';
import { SHIPMENT_STATUS_LABELS, formatKg, formatM3 } from './shipment-labels';
import { ShipmentStatusBadgeComponent } from './shipment-status-badge.component';

@Component({
  selector: 'app-shipment-detail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink, ShipmentStatusBadgeComponent, AttachmentPanelComponent, CancelDialogComponent, QuotePanelComponent, ApplicantsPanelComponent],
  template: `
  <main class="container">
    <a [routerLink]="admin() ? '/admin/shipments' : '/customer/shipments'" class="kicker">← Danh sách đơn</a>
    @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:14px">{{ error() }}</div> }
    @if (notice()) { <div class="alert alert-success" role="status" style="margin-top:14px">{{ notice() }}</div> }
    @if (shipment(); as s) {
      <div class="page-head"><div><h1>{{ s.shipmentCode }}</h1><span class="muted">Tạo lúc {{ s.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span></div>
        <div class="actions-row"><app-shipment-status [status]="s.status" />
          @if (s.actions.editable) { <a class="btn btn-ghost" [routerLink]="['/customer/shipments', s.id, 'edit']">Sửa đơn</a> }
          @if (s.actions.publishable) { <button class="btn btn-primary" type="button" (click)="publish(s)" [disabled]="busy()">{{ busy() ? 'Đang xử lý…' : 'Đăng đơn' }}</button> }
          @if (s.actions.cancellable) { <button class="btn btn-ghost" type="button" (click)="cancelOpen.set(true)" [disabled]="busy()">Hủy đơn</button> }
          @if (s.actions.deletable) {
            @if (confirmDelete()) {
              <button class="btn btn-danger" type="button" (click)="remove(s)" [disabled]="busy()">Xác nhận xóa nháp</button>
              <button class="btn btn-ghost" type="button" (click)="confirmDelete.set(false)">Không</button>
            } @else { <button class="btn btn-ghost" type="button" (click)="confirmDelete.set(true)">Xóa nháp</button> }
          }
        </div></div>
      @for (w of s.warnings; track w.code) { <div class="alert alert-warn">{{ w.message }}</div> }
      @if (s.status === 'CANCELLED') { <div class="alert alert-error">Đơn đã hủy lúc {{ s.cancelledAt | date: 'HH:mm dd/MM/yyyy' }}: {{ s.cancellationReason }}</div> }
      @if (s.status === 'PUBLISHED') {
        <div class="alert alert-info">@if (s.priceStatus === 'ACCEPTED') { Đơn đang hiển thị cho tài xế. Chọn một tài xế trong mục "Tài xế muốn nhận đơn". }
          @else { Đơn đã đăng nhưng chưa có giá được xác nhận, nên tài xế chưa thấy đơn. Hãy xác nhận báo giá. }</div>
      }
      @if (s.status === 'EXPIRED') { <div class="alert alert-warn">Đơn đã hết hạn vì quá giờ lấy hàng mà chưa chọn tài xế.</div> }
      <div class="detail-grid">
        <div>
          <section class="card"><h2>Lộ trình</h2>
            @for (stop of s.stops; track stop.id) {
              <div class="stop"><span class="badge" [class.badge--approved]="stop.stopType === 'PICKUP'">{{ stop.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</span>
                <div><strong>{{ stop.contactName }}</strong> · {{ stop.contactPhone }}<br>{{ stop.fullAddress }}
                  @if (stop.latitude !== null) { <br><span class="muted">GPS {{ stop.latitude }}, {{ stop.longitude }}</span> }
                  @if (stop.note) { <br><em class="muted">{{ stop.note }}</em> }</div></div>
            }
          </section>
          <section class="card"><h2>Hàng hóa</h2>
            <div class="table-wrap"><table class="table"><thead><tr><th>Tên</th><th>Loại</th><th>SL</th><th>Kg/kiện</th><th>Kích thước</th><th>Tổng kg</th><th>m³</th></tr></thead>
              <tbody>@for (i of s.items; track i.id) {
                <tr><td>{{ i.itemName }}@if (i.description) { <br><span class="muted">{{ i.description }}</span> }</td><td>{{ i.categoryName }}</td><td>{{ i.quantity }}</td>
                  <td>{{ i.weightPerUnitKg | number: '1.0-2' }}</td><td>{{ i.lengthCm ? i.lengthCm + '×' + i.widthCm + '×' + i.heightCm + ' cm' : '—' }}</td>
                  <td>{{ i.totalWeightKg | number: '1.0-2' }}</td><td>{{ i.totalVolumeM3 === null ? '—' : (i.totalVolumeM3 | number: '1.0-3') }}</td></tr>
              }</tbody></table></div>
          </section>
          <app-attachment-panel [shipmentId]="s.id" [initial]="s.attachments" [editable]="s.actions.attachmentsEditable" />
        </div>
        <aside>
          @if (s.assignment; as d) {
            <section class="card assigned" data-testid="assigned-driver"><h2>Tài xế đã nhận</h2>
              <dl class="dl compact"><dt>Tài xế</dt><dd><strong>{{ d.fullName || '—' }}</strong></dd>
                <dt>Điện thoại</dt><dd>@if (d.phone) { <a [href]="'tel:' + d.phone">{{ d.phone }}</a> } @else { — }</dd>
                <dt>Xe</dt><dd>{{ d.plateNumber }}@if (d.maxPayloadKg) { · {{ d.maxPayloadKg | number }} kg }</dd>
                <dt>Nhận lúc</dt><dd>{{ d.assignedAt | date: 'HH:mm dd/MM/yyyy' }}</dd></dl>
            </section>
          }
          @if (!admin() && s.applications && (s.status === 'PUBLISHED' || s.applications.total > 0)) {
            <app-applicants-panel [shipmentId]="s.id" [canChoose]="s.status === 'PUBLISHED' && !s.assignment" (changed)="reload()" />
          }
          <app-quote-panel [shipmentId]="s.id" [owner]="!admin()" [shipmentOpen]="s.status === 'DRAFT' || s.status === 'PUBLISHED'" (changed)="reload()" />
          <section class="card"><h2>Tổng quan</h2>
            <dl class="dl compact">
              <dt>Lấy hàng</dt><dd>{{ s.pickupTime ? (s.pickupTime | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
              <dt>Giao trước</dt><dd>{{ s.deliveryDeadline ? (s.deliveryDeadline | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
              <dt>Tổng kiện</dt><dd>{{ s.totalQuantity }}</dd><dt>Trọng lượng</dt><dd>{{ kg(s.totalWeightKg) }}</dd><dt>Thể tích</dt><dd>{{ m3(s.totalVolumeM3) }}</dd>
              <dt>Loại xe</dt><dd>{{ s.vehicle.vehicleTypeName || 'Hệ thống gợi ý' }}@if (s.vehicle.vehicleCount > 1) { × {{ s.vehicle.vehicleCount }} }</dd>
              <dt>Loại thùng</dt><dd>{{ s.vehicle.bodyTypeName || 'Không yêu cầu' }}</dd>
              <dt>Giá cước</dt><dd>{{ priceText(s) }}</dd>
            </dl>
          </section>
          <section class="card"><h2>Dịch vụ bổ sung</h2>
            @for (x of s.services; track x.serviceCode) { <p class="svc">✓ {{ serviceText(x) }}</p> }
            @empty { <p class="muted">Không có.</p> }
            @if (s.customerNote) { <p><strong>Ghi chú:</strong> {{ s.customerNote }}</p> }
          </section>
          <section class="card"><h2>Lịch sử trạng thái</h2>
            <ol class="timeline">@for (h of s.statusHistory; track h.createdAt) {
              <li><strong>{{ statusLabels[h.newStatus] }}</strong><span class="muted">{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span>@if (h.changeReason) { <em>{{ h.changeReason }}</em> }</li>
            }</ol>
          </section>
        </aside>
      </div>
      @if (cancelOpen()) { <app-cancel-dialog [shipmentId]="s.id" [shipmentCode]="s.shipmentCode" (closed)="cancelOpen.set(false)" (cancelled)="afterCancel($event)" /> }
    } @else if (!error()) { <p class="muted" aria-busy="true" style="margin-top:20px">Đang tải đơn…</p> }
  </main>`,
  styles: [`.detail-grid{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:20px}.stop{display:flex;gap:12px;padding:10px 0;border-bottom:1px solid #eef1ea;font-size:14px}.stop .badge{min-width:82px;text-align:center;align-self:flex-start}
  .dl.compact{grid-template-columns:110px 1fr}.assigned{border-color:#8eb85a}.svc{margin:4px 0;font-size:14px}.timeline{list-style:none;padding:0;margin:0}.timeline li{display:flex;flex-direction:column;gap:2px;padding:8px 0 8px 14px;border-left:2px solid #cfe0bd;font-size:13px}
  @media(max-width:900px){.detail-grid{grid-template-columns:minmax(0,1fr)}}`],
})
export class ShipmentDetailComponent implements OnInit {
  readonly admin = input(false);
  private readonly api = inject(ShipmentApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly shipment = signal<ShipmentDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(this.route.snapshot.queryParamMap.get('published') ? 'Đã đăng đơn thành công.' : null);
  protected readonly busy = signal(false);
  protected readonly cancelOpen = signal(false);
  protected readonly confirmDelete = signal(false);
  protected readonly statusLabels = SHIPMENT_STATUS_LABELS;
  protected readonly pricePending = 'Chưa có báo giá – bấm "Tính giá" trong mục Báo giá';
  protected readonly kg = formatKg;
  protected readonly m3 = formatM3;

  ngOnInit(): void { this.reload(); }

  /** Re-reads the order, e.g. after a quote changed its price state. */
  reload(): void {
    this.api.get(this.route.snapshot.paramMap.get('id') ?? '').subscribe({ next: s => this.shipment.set(s), error: (e: unknown) => this.error.set(toApiError(e).message) });
  }

  /** Price state from the API; never a number the UI made up. */
  priceText(s: ShipmentDetail): string {
    switch (s.priceStatus) {
      case 'QUOTED': return `${formatVnd(s.estimatedPrice)} (chờ xác nhận)`;
      case 'ACCEPTED': return `${formatVnd(s.estimatedPrice)} (đã xác nhận)`;
      case 'FINAL': return formatVnd(s.finalPrice);
      case 'AWAITING_REVIEW': return 'Đang chờ quản trị viên xác nhận';
      default: return this.pricePending;
    }
  }

  publish(s: ShipmentDetail): void {
    this.run(this.api.publish(s.id), updated => { this.shipment.set(updated); this.notice.set('Đã đăng đơn thành công.'); });
  }

  serviceText(x: SelectedService): string {
    const detail = x.valueLabel ?? (x.valueType === 'NUMBER' || x.valueType === 'TEXT' ? x.serviceValue : null);
    return detail ? `${x.serviceName}: ${detail}` : x.serviceName;
  }

  afterCancel(updated: ShipmentDetail): void { this.cancelOpen.set(false); this.shipment.set(updated); this.notice.set('Đã hủy đơn.'); }

  remove(s: ShipmentDetail): void {
    this.run(this.api.delete(s.id), () => void this.router.navigate(['/customer/shipments']));
  }

  private run<T>(request: Observable<T>, done: (value: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    request.subscribe({
      next: value => { this.busy.set(false); done(value); },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.error.set(Object.values(err.fieldErrors).length ? `${err.message}: ${Object.values(err.fieldErrors).join(' ')}` : err.message);
      },
    });
  }
}
