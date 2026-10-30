import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import { ShipmentApi, ShipmentQuery, ShipmentStatus, ShipmentSummary } from '../../core/api/shipment.api';
import { CancelDialogComponent } from './cancel-dialog.component';
import { SHIPMENT_STATUS_LABELS, formatKg } from './shipment-labels';
import { ShipmentStatusBadgeComponent } from './shipment-status-badge.component';

/** Customer's own orders, or every order when used in the admin area (read-only). */
@Component({
  selector: 'app-shipment-list',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, RouterLink, ShipmentStatusBadgeComponent, CancelDialogComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">{{ admin() ? 'Quản trị' : 'Khu vực khách hàng' }}</div><h1>{{ admin() ? 'Đơn vận chuyển' : 'Đơn vận chuyển của tôi' }}</h1></div>
      @if (!admin()) { <a class="btn btn-primary" routerLink="/customer/shipments/create">+ Tạo đơn mới</a> }</div>
    <form class="card filters" [formGroup]="filters" (ngSubmit)="search()">
      <div class="field"><label for="keyword">Tìm kiếm</label><input id="keyword" formControlName="keyword" placeholder="Mã đơn, địa chỉ, người liên hệ" /></div>
      <div class="field"><label for="status">Trạng thái</label>
        <select id="status" formControlName="status"><option value="">Tất cả</option>
          @for (s of statuses; track s) { <option [value]="s">{{ statusLabels[s] }}</option> }</select></div>
      <div class="field"><label for="fromDate">Tạo từ ngày</label><input id="fromDate" type="date" formControlName="fromDate" /></div>
      <div class="field"><label for="toDate">Đến ngày</label><input id="toDate" type="date" formControlName="toDate" /></div>
      <div class="field"><label for="sort">Sắp xếp</label>
        <select id="sort" formControlName="sort"><option value="createdAt:desc">Mới nhất</option><option value="createdAt:asc">Cũ nhất</option>
          <option value="pickupTime:asc">Ngày lấy hàng gần nhất</option><option value="totalWeightKg:desc">Nặng nhất</option></select></div>
      <div class="filter-actions"><button class="btn btn-primary" type="submit" [disabled]="loading()">Lọc</button>
        <button class="btn btn-ghost" type="button" (click)="reset()" [disabled]="loading()">Xóa lọc</button></div>
    </form>
    @if (notice()) { <div class="alert alert-success" role="status">{{ notice() }}</div> }
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }} <button class="btn btn-ghost" type="button" (click)="load(page())">Thử lại</button></div> }
    <section class="card">
      @if (loading()) {
        <div aria-busy="true" aria-label="Đang tải">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div>
      } @else if (data(); as d) {
        @if (d.items.length === 0) {
          <div class="empty">@if (filtered()) { Không có đơn nào phù hợp bộ lọc. } @else { Bạn chưa có đơn vận chuyển nào. }
            @if (!admin() && !filtered()) { <br><a class="btn btn-primary" style="margin-top:14px" routerLink="/customer/shipments/create">Tạo đơn đầu tiên</a> }</div>
        } @else {
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Mã đơn</th><th>Lộ trình</th><th>Hàng hóa</th><th>Loại xe</th><th>Lấy hàng</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>
              @for (s of d.items; track s.id) {
                <tr>
                  <td><a [routerLink]="detailLink(s)"><strong>{{ s.shipmentCode }}</strong></a><br><span class="muted">{{ s.createdAt | date: 'dd/MM/yyyy HH:mm' }}</span></td>
                  <td class="route"><span>↑ {{ s.pickupAddress }}</span><span>↓ {{ s.deliveryAddress }}@if (s.deliveryStopCount > 1) { <em class="muted"> (+{{ s.deliveryStopCount - 1 }} điểm)</em> }</span></td>
                  <td>{{ s.totalQuantity }} kiện<br><span class="muted">{{ kg(s.totalWeightKg) }}</span></td>
                  <td>{{ s.vehicleTypeName || 'Hệ thống gợi ý' }}</td>
                  <td>{{ s.pickupTime ? (s.pickupTime | date: 'dd/MM HH:mm') : '—' }}</td>
                  <td><app-shipment-status [status]="s.status" /></td>
                  <td><div class="actions-row">
                    <a class="btn btn-ghost" [routerLink]="detailLink(s)">Xem</a>
                    @if (s.actions.editable) { <a class="btn btn-ghost" [routerLink]="['/customer/shipments', s.id, 'edit']">Sửa</a> }
                    @if (s.actions.cancellable) { <button class="btn btn-ghost" type="button" (click)="cancelTarget.set(s)">Hủy</button> }
                  </div></td>
                </tr>
              }
            </tbody>
          </table></div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }} · {{ d.totalElements }} đơn</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
    @if (cancelTarget(); as target) {
      <app-cancel-dialog [shipmentId]="target.id" [shipmentCode]="target.shipmentCode" (closed)="cancelTarget.set(null)" (cancelled)="afterCancel(target.shipmentCode)" />
    }
  </main>`,
  styles: [`.filters{display:grid;grid-template-columns:2fr 1fr 1fr 1fr 1fr;gap:0 12px;align-items:end}.filter-actions{display:flex;gap:8px;grid-column:1/-1}
  .route span{display:block;max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.skeleton-row{height:44px;margin:8px 0;border-radius:4px;background:linear-gradient(90deg,#eef1ea,#f7f8f4,#eef1ea);background-size:200% 100%;animation:sh 1.2s infinite}
  @keyframes sh{to{background-position:-200% 0}}@media(max-width:900px){.filters{grid-template-columns:1fr 1fr}}@media(max-width:560px){.filters{grid-template-columns:1fr}}`],
})
export class ShipmentListComponent implements OnInit {
  /** Set from the route data in the admin area. */
  readonly admin = input(false);
  private readonly api = inject(ShipmentApi);
  protected readonly statuses: ShipmentStatus[] = ['DRAFT', 'PUBLISHED', 'CANCELLED', 'ASSIGNED', 'IN_TRANSIT', 'COMPLETED'];
  protected readonly statusLabels = SHIPMENT_STATUS_LABELS;
  protected readonly kg = formatKg;
  protected readonly data = signal<PageResponse<ShipmentSummary> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly cancelTarget = signal<ShipmentSummary | null>(null);
  protected readonly filters = inject(FormBuilder).nonNullable.group({ keyword: '', status: '' as ShipmentStatus | '', fromDate: '', toDate: '', sort: 'createdAt:desc' });
  protected readonly filtered = signal(false);
  protected readonly pageSize = computed(() => (this.admin() ? 20 : 10));

  ngOnInit(): void { this.load(0); }

  search(): void { this.load(0); }

  reset(): void { this.filters.reset(); this.load(0); }

  detailLink(s: ShipmentSummary): string[] { return this.admin() ? ['/admin/shipments', s.id] : ['/customer/shipments', s.id]; }

  afterCancel(code: string): void { this.cancelTarget.set(null); this.notice.set(`Đã hủy đơn ${code}.`); this.load(this.page()); }

  load(page: number): void {
    const f = this.filters.getRawValue();
    const [sortBy, sortDirection] = f.sort.split(':') as [string, 'asc' | 'desc'];
    const query: ShipmentQuery = { page: Math.max(page, 0), size: this.pageSize(), status: f.status, keyword: f.keyword, fromDate: f.fromDate, toDate: f.toDate, sortBy, sortDirection };
    this.filtered.set(Boolean(f.keyword || f.status || f.fromDate || f.toDate));
    this.loading.set(true);
    this.error.set(null);
    (this.admin() ? this.api.all(query) : this.api.mine(query)).subscribe({
      next: data => { this.data.set(data); this.page.set(data.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }
}
