import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import { DriverTripApi, TripSummary, TripView } from '../../core/api/trip.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { formatVnd } from '../../shared/money';
import { formatKg } from '../shipments/shipment-labels';
import { ShipmentStatusBadgeComponent } from '../shipments/shipment-status-badge.component';

@Component({
  selector: 'app-driver-trips',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, ShipmentStatusBadgeComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Chuyến của tôi</h1></div></div>
    <div class="segmented" role="tablist" aria-label="Nhóm chuyến">
      @for (t of tabs; track t.value) {
        <button type="button" role="tab" [class.active]="view() === t.value" [attr.aria-selected]="view() === t.value" (click)="select(t.value)">{{ t.label }}</button>
      }
    </div>
    <form class="card filters" (ngSubmit)="load(0)">
      <div class="field"><label for="tr-kw">Tìm kiếm</label><input id="tr-kw" name="keyword" [(ngModel)]="keyword" placeholder="Mã đơn, địa chỉ" /></div>
      <div class="field"><label for="tr-from">Lấy hàng từ ngày</label><input id="tr-from" name="fromDate" type="date" [(ngModel)]="fromDate" /></div>
      <div class="field"><label for="tr-to">Đến ngày</label><input id="tr-to" name="toDate" type="date" [(ngModel)]="toDate" /></div>
      <div class="filter-actions"><button class="btn btn-primary" type="submit" [disabled]="loading()">Lọc</button></div>
    </form>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }} <button class="btn btn-ghost" type="button" (click)="load(page())">Thử lại</button></div> }
    <section aria-live="polite">
      @if (loading()) { <div class="card" aria-busy="true">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div> }
      @else if (data(); as d) {
        @if (d.items.length === 0) {
          <div class="card empty">{{ emptyText() }} @if (view() !== 'COMPLETED') { <br><a class="btn btn-primary" style="margin-top:14px" routerLink="/driver/marketplace">Tìm đơn</a> }</div>
        } @else {
          <div class="cards">
            @for (t of d.items; track t.id) {
              <a class="card trip" [routerLink]="['/driver/trips', t.id]" data-testid="trip-item">
                <div class="head"><strong>{{ t.shipmentCode }}</strong><app-shipment-status [status]="t.status" /></div>
                @if (t.openIncidents) { <span class="badge badge--rejected">{{ t.openIncidents }} sự cố đang mở</span> }
                <div class="route"><span>↑ {{ t.pickupAddress }}</span><span>↓ {{ t.deliveryAddress }}@if (t.stopCount > 2) { <em class="muted"> ({{ t.stopCount }} điểm)</em> }</span></div>
                <dl class="facts"><dt>Lấy hàng</dt><dd>{{ t.pickupTime ? (t.pickupTime | date: 'HH:mm dd/MM/yyyy') : '—' }}</dd>
                  <dt>Hàng</dt><dd>{{ t.totalQuantity }} kiện · {{ kg(t.totalWeightKg) }}</dd><dt>Giá</dt><dd>{{ vnd(t.price) }}</dd>
                  @if (t.vehicle; as v) { <dt>Xe</dt><dd>{{ v.plateNumber }} · {{ vehicleLabels[v.vehicleType] }}</dd> }</dl>
              </a>
            }
          </div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }}</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
  </main>`,
  styles: [`.segmented{margin-bottom:14px;flex-wrap:wrap}.filters{display:grid;grid-template-columns:2fr 1fr 1fr auto;gap:0 12px;align-items:end}.filter-actions{padding-bottom:14px}
  .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px}.trip{display:flex;flex-direction:column;gap:8px;margin:0;color:inherit;text-decoration:none}
  .trip:hover,.trip:focus-visible{border-color:#8eb85a}.head{display:flex;justify-content:space-between;align-items:center;gap:8px}.route span{display:block;font-size:14px;overflow:hidden;text-overflow:ellipsis}
  .facts{display:grid;grid-template-columns:80px minmax(0,1fr);gap:2px 8px;margin:0;font-size:13px}.facts dt{color:var(--tg-muted)}.facts dd{margin:0}
  @media(max-width:700px){.filters{grid-template-columns:minmax(0,1fr)}}`],
})
export class DriverTripsComponent implements OnInit {
  private readonly api = inject(DriverTripApi);
  protected readonly tabs: { value: TripView; label: string }[] = [
    { value: 'ACTIVE', label: 'Đang thực hiện' }, { value: 'UPCOMING', label: 'Sắp thực hiện' }, { value: 'COMPLETED', label: 'Đã hoàn thành' },
    { value: 'INCIDENT', label: 'Có sự cố' }, { value: 'ALL', label: 'Tất cả' },
  ];
  protected readonly view = signal<TripView>('ACTIVE');
  protected readonly data = signal<PageResponse<TripSummary> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly vehicleLabels = VEHICLE_TYPE_LABELS;
  protected keyword = '';
  protected fromDate = '';
  protected toDate = '';
  protected readonly vnd = formatVnd;
  protected readonly kg = formatKg;

  ngOnInit(): void { this.load(0); }

  select(view: TripView): void { this.view.set(view); this.load(0); }

  emptyText(): string {
    return { ACTIVE: 'Bạn không có chuyến nào đang thực hiện.', UPCOMING: 'Chưa có chuyến nào sắp thực hiện.', COMPLETED: 'Chưa có chuyến nào hoàn thành.',
      INCIDENT: 'Không có chuyến nào có sự cố đang mở.', ALL: 'Bạn chưa được phân công chuyến nào.' }[this.view()];
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list({ view: this.view(), keyword: this.keyword.trim(), fromDate: this.fromDate, toDate: this.toDate, page: Math.max(page, 0) }).subscribe({
      next: d => { this.data.set(d); this.page.set(d.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }
}
