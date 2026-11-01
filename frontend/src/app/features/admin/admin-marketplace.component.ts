import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import {
  APPLICATION_STATUS_LABELS, AdminApplication, AdminAssignment, AdminMarketplaceApi, AdminMarketplaceShipment, ApplicationStatus, ScheduleEntry, areaText,
} from '../../core/api/marketplace.api';
import { ShipmentStatus } from '../../core/api/shipment.api';
import { formatVnd } from '../../shared/money';
import { applicationBadge } from '../marketplace/marketplace-labels';
import { SHIPMENT_STATUS_LABELS } from '../shipments/shipment-labels';
import { ShipmentStatusBadgeComponent } from '../shipments/shipment-status-badge.component';

type Tab = 'shipments' | 'applications' | 'assignments' | 'schedules';

/**
 * Marketplace monitoring: orders (including those still missing drivers), every application, the assignment history
 * and the busy periods of drivers and vehicles. Removing a driver is done from the order's detail page.
 */
@Component({
  selector: 'app-admin-marketplace',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, ShipmentStatusBadgeComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Marketplace</h1></div></div>
    <div class="segmented" role="tablist" aria-label="Chế độ xem">
      <button type="button" role="tab" [class.active]="tab() === 'shipments'" [attr.aria-selected]="tab() === 'shipments'" (click)="show('shipments')">Đơn</button>
      <button type="button" role="tab" [class.active]="tab() === 'applications'" [attr.aria-selected]="tab() === 'applications'" (click)="show('applications')">Yêu cầu nhận đơn</button>
      <button type="button" role="tab" [class.active]="tab() === 'assignments'" [attr.aria-selected]="tab() === 'assignments'" (click)="show('assignments')">Lịch sử phân công</button>
      <button type="button" role="tab" [class.active]="tab() === 'schedules'" [attr.aria-selected]="tab() === 'schedules'" (click)="show('schedules')">Lịch tài xế & xe</button>
    </div>
    <div class="card filters">
      @if (tab() === 'shipments') {
        <div class="field"><label for="am-status">Trạng thái đơn</label>
          <select id="am-status" [(ngModel)]="shipmentStatus" (ngModelChange)="load(0)"><option value="">Đang hoạt động</option>
            @for (s of shipmentStatuses; track s) { <option [value]="s">{{ shipmentLabels[s] }}</option> }</select></div>
        <div class="field"><label for="am-kw">Mã đơn</label><input id="am-kw" [(ngModel)]="keyword" (keyup.enter)="load(0)" placeholder="TG..." /></div>
        <label class="check"><input type="checkbox" [(ngModel)]="understaffed" (ngModelChange)="load(0)" data-testid="understaffed" /> Chỉ đơn chưa đủ tài xế</label>
      } @else if (tab() === 'applications') {
        <div class="field"><label for="am-astatus">Trạng thái yêu cầu</label>
          <select id="am-astatus" [(ngModel)]="applicationStatus" (ngModelChange)="load(0)"><option value="">Tất cả</option>
            @for (s of applicationStatuses; track s) { <option [value]="s">{{ applicationLabels[s] }}</option> }</select></div>
      } @else if (tab() === 'schedules') {
        <div class="field"><label for="am-driver">Mã tài xế (driverId)</label><input id="am-driver" [(ngModel)]="driverId" (keyup.enter)="load(0)" placeholder="Để trống: tất cả" /></div>
        <div class="field"><label for="am-from">Từ ngày</label><input id="am-from" type="date" [(ngModel)]="fromDate" (ngModelChange)="load(0)" /></div>
      }
      <div class="filter-actions"><button class="btn btn-ghost" type="button" (click)="load(page())" [disabled]="loading()">Tải lại</button></div>
    </div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    <section class="card">
      @if (loading()) { <div aria-busy="true">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div> }
      @else {
        @if (tab() === 'shipments' && shipments(); as d) {
          @if (d.items.length === 0) { <p class="empty">Không có đơn nào.</p> } @else {
            <div class="table-wrap"><table class="table"><thead><tr><th>Mã đơn</th><th>Tuyến</th><th>Lấy hàng</th><th>Giá</th><th>Yêu cầu</th><th>Tài xế</th><th>Trạng thái</th></tr></thead>
              <tbody>@for (s of d.items; track s.id) {
                <tr><td><a [routerLink]="['/admin/shipments', s.id]"><strong>{{ s.shipmentCode }}</strong></a>
                  @if (s.vehicleCount > 1 || s.assignedVehicleCount < s.vehicleCount && s.status === 'PUBLISHED') {
                    <br><span class="muted" data-testid="admin-vehicle-progress">{{ s.assignedVehicleCount }}/{{ s.vehicleCount }} xe đã có tài xế</span> }</td>
                  <td>{{ area(s.pickup) }} → {{ area(s.delivery) }}</td><td>{{ s.pickupTime ? (s.pickupTime | date: 'HH:mm dd/MM') : '—' }}</td><td>{{ vnd(s.price) }}</td>
                  <td>{{ s.pendingApplications }} chờ / {{ s.totalApplications }}</td><td>{{ s.assignedDriverName || '—' }}</td><td><app-shipment-status [status]="s.status" /></td></tr>
              }</tbody></table></div>
          }
        }
        @if (tab() === 'applications' && applications(); as d) {
          @if (d.items.length === 0) { <p class="empty">Chưa có yêu cầu nào.</p> } @else {
            <div class="table-wrap"><table class="table"><thead><tr><th>Đơn</th><th>Tài xế</th><th>Xe</th><th>Gửi lúc</th><th>Phản hồi</th><th>Trạng thái</th></tr></thead>
              <tbody>@for (a of d.items; track a.id) {
                <tr><td><a [routerLink]="['/admin/shipments', a.shipmentId]">{{ a.shipmentCode }}</a></td><td>{{ a.driverName }}<br><span class="muted">{{ a.driverPhone }}</span></td>
                  <td>{{ a.plateNumber }}</td><td>{{ a.appliedAt | date: 'HH:mm dd/MM' }}</td><td>{{ a.responseReason || '—' }}</td>
                  <td><span [class]="badge(a.status)">{{ applicationLabels[a.status] }}</span></td></tr>
              }</tbody></table></div>
          }
        }
        @if (tab() === 'assignments' && assignments(); as d) {
          @if (d.items.length === 0) { <p class="empty">Chưa có lượt phân công nào.</p> } @else {
            <div class="table-wrap"><table class="table"><thead><tr><th>Thời điểm</th><th>Đơn</th><th>Hành động</th><th>Tài xế</th><th>Xe</th><th>Lý do</th><th>Thực hiện bởi</th></tr></thead>
              <tbody>@for (h of d.items; track h.id) {
                <tr><td>{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }}</td><td><a [routerLink]="['/admin/shipments', h.shipmentId]">{{ h.shipmentCode }}</a></td>
                  <td>{{ actionLabels[h.action] }}</td>
                  <td>@switch (h.action) { @case ('ASSIGNED') { {{ h.newDriverName || '—' }} } @case ('UNASSIGNED') { {{ h.previousDriverName || '—' }} }
                    @case ('REPLACED') { {{ h.previousDriverName || '—' }} → {{ h.newDriverName || '—' }} } }</td>
                  <td>{{ h.plateNumber || '—' }}</td><td>{{ h.reason || '—' }}</td>
                  <td>{{ h.actorType === 'SYSTEM' ? 'Hệ thống (' + h.systemActor + ')' : (h.performedByName || '—') }}</td></tr>
              }</tbody></table></div>
          }
        }
        @if (tab() === 'schedules' && schedules(); as d) {
          @if (d.items.length === 0) { <p class="empty">Không có lịch bận trong khoảng thời gian này.</p> } @else {
            <div class="table-wrap"><table class="table"><thead><tr><th>Bận từ</th><th>Đến</th><th>Tài xế</th><th>Xe</th><th>Đơn</th><th>Trạng thái đơn</th></tr></thead>
              <tbody>@for (e of d.items; track e.assignmentId) {
                <tr data-testid="schedule-row"><td>{{ e.busyFrom | date: 'HH:mm dd/MM/yyyy' }}</td><td>{{ e.busyUntil | date: 'HH:mm dd/MM/yyyy' }}</td>
                  <td>{{ e.driverName || '—' }}@if (e.lead) { <span class="badge">chạy chuyến</span> }</td><td>{{ e.plateNumber }}</td>
                  <td><a [routerLink]="['/admin/shipments', e.shipmentId]">{{ e.shipmentCode }}</a></td><td><app-shipment-status [status]="e.shipmentStatus" /></td></tr>
              }</tbody></table></div>
          }
        }
        @if (current(); as d) {
          @if (d.totalPages > 1) {
            <div class="pager">
              <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
              <span>Trang {{ d.page + 1 }} / {{ d.totalPages }}</span>
              <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
            </div>
          }
        }
      }
    </section>
  </main>`,
  styles: [`.segmented{margin-bottom:14px;flex-wrap:wrap}.filters{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.check{display:flex;gap:6px;align-items:center;font-size:14px;padding-bottom:10px}.filter-actions{margin-left:auto}`],
})
export class AdminMarketplaceComponent implements OnInit {
  private readonly api = inject(AdminMarketplaceApi);
  protected readonly tab = signal<Tab>('shipments');
  protected readonly shipments = signal<PageResponse<AdminMarketplaceShipment> | null>(null);
  protected readonly applications = signal<PageResponse<AdminApplication> | null>(null);
  protected readonly assignments = signal<PageResponse<AdminAssignment> | null>(null);
  protected readonly schedules = signal<PageResponse<ScheduleEntry> | null>(null);
  protected understaffed = false;
  protected driverId = '';
  protected fromDate = '';
  protected readonly actionLabels: Record<AdminAssignment['action'], string> = { ASSIGNED: 'Giao cho tài xế', UNASSIGNED: 'Gỡ tài xế', REPLACED: 'Thay tài xế' };
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected shipmentStatus: ShipmentStatus | '' = '';
  protected applicationStatus: ApplicationStatus | '' = '';
  protected keyword = '';
  protected readonly shipmentStatuses: ShipmentStatus[] = ['PUBLISHED', 'ASSIGNED', 'EXPIRED', 'CANCELLED'];
  protected readonly applicationStatuses: ApplicationStatus[] = ['PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'RELEASED'];
  protected readonly shipmentLabels = SHIPMENT_STATUS_LABELS;
  protected readonly applicationLabels = APPLICATION_STATUS_LABELS;
  protected readonly area = areaText;
  protected readonly vnd = formatVnd;
  protected readonly badge = applicationBadge;

  ngOnInit(): void { this.load(0); }

  show(tab: Tab): void { this.tab.set(tab); this.load(0); }

  current(): PageResponse<unknown> | null {
    switch (this.tab()) {
      case 'shipments': return this.shipments();
      case 'applications': return this.applications();
      case 'assignments': return this.assignments();
      default: return this.schedules();
    }
  }

  load(page: number): void {
    page = Math.max(page, 0);
    this.loading.set(true);
    this.error.set(null);
    const fail = (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); };
    const done = () => { this.page.set(page); this.loading.set(false); };
    switch (this.tab()) {
      case 'shipments': this.api.shipments(this.shipmentStatus, this.keyword.trim(), page, this.understaffed).subscribe({ next: d => { this.shipments.set(d); done(); }, error: fail }); break;
      case 'applications': this.api.applications(this.applicationStatus, page).subscribe({ next: d => { this.applications.set(d); done(); }, error: fail }); break;
      case 'assignments': this.api.assignments(page).subscribe({ next: d => { this.assignments.set(d); done(); }, error: fail }); break;
      case 'schedules':
        this.api.schedules({ driverId: this.driverId.trim() || undefined, from: this.fromDate ? new Date(this.fromDate).toISOString() : undefined, page })
          .subscribe({ next: d => { this.schedules.set(d); done(); }, error: fail });
        break;
    }
  }
}
