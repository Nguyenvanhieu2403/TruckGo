import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import { APPLICATION_STATUS_LABELS, DriverMarketplaceApi, MarketplaceListItem, MarketplaceQuery, MarketplaceSort, areaText } from '../../core/api/marketplace.api';
import { CatalogApi } from '../../core/api/catalog.api';
import { formatVnd } from '../../shared/money';
import { formatKg } from '../shipments/shipment-labels';
import { applicationBadge, bodyTypeLabel, formatKm, vehicleProgress } from './marketplace-labels';

/** Open orders for drivers: area-level route, cargo, the agreed price and the application deadline. */
@Component({
  selector: 'app-driver-marketplace',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Tìm đơn vận chuyển</h1>
      <span class="muted">Đơn đã được khách hàng xác nhận giá. Địa chỉ chính xác và liên hệ chỉ hiển thị khi bạn được chọn.</span></div></div>
    <form class="card filters" [formGroup]="filters" (ngSubmit)="load(0)">
      <div class="field wide"><label for="mk-keyword">Tìm kiếm</label><input id="mk-keyword" formControlName="keyword" placeholder="Mã đơn, khu vực, tên hàng" /></div>
      <div class="field"><label for="mk-pp">Tỉnh/TP lấy hàng</label><input id="mk-pp" formControlName="pickupProvince" placeholder="VD: Hà Nội" /></div>
      <div class="field"><label for="mk-pd">Quận/huyện lấy</label><input id="mk-pd" formControlName="pickupDistrict" /></div>
      <div class="field"><label for="mk-dp">Tỉnh/TP giao hàng</label><input id="mk-dp" formControlName="deliveryProvince" /></div>
      <div class="field"><label for="mk-dd">Quận/huyện giao</label><input id="mk-dd" formControlName="deliveryDistrict" /></div>
      <div class="field"><label for="mk-vt">Loại xe</label>
        <select id="mk-vt" formControlName="vehicleTypeId"><option value="">Tất cả</option>
          @for (t of vehicleTypes(); track t.code) { <option [value]="t.code">{{ t.name }}</option> }</select></div>
      <div class="field"><label for="mk-minp">Giá từ (VNĐ)</label><input id="mk-minp" type="number" min="0" formControlName="minPrice" /></div>
      <div class="field"><label for="mk-maxp">Giá đến (VNĐ)</label><input id="mk-maxp" type="number" min="0" formControlName="maxPrice" /></div>
      <div class="field"><label for="mk-minw">Khối lượng từ (kg)</label><input id="mk-minw" type="number" min="0" formControlName="minWeight" /></div>
      <div class="field"><label for="mk-maxw">Khối lượng đến (kg)</label><input id="mk-maxw" type="number" min="0" formControlName="maxWeight" /></div>
      <div class="field"><label for="mk-maxd">Quãng đường tối đa (km)</label><input id="mk-maxd" type="number" min="0" formControlName="maxDistance" /></div>
      <div class="field"><label for="mk-sort">Sắp xếp</label>
        <select id="mk-sort" formControlName="sort">
          <option value="pickupTime:asc">Lấy hàng sớm nhất</option><option value="publishedAt:desc">Mới đăng</option>
          <option value="price:desc">Giá cao nhất</option><option value="price:asc">Giá thấp nhất</option>
          <option value="distance:asc">Quãng đường ngắn nhất</option><option value="weight:desc">Nặng nhất</option></select></div>
      <div class="filter-actions"><button class="btn btn-primary" type="submit" [disabled]="loading()">Lọc</button>
        <button class="btn btn-ghost" type="button" (click)="reset()" [disabled]="loading()">Xóa lọc</button></div>
    </form>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }} <button class="btn btn-ghost" type="button" (click)="load(page())">Thử lại</button></div> }
    <section aria-live="polite">
      @if (loading()) {
        <div class="card" aria-busy="true" aria-label="Đang tải">@for (n of [1, 2, 3, 4]; track n) { <div class="skeleton-row"></div> }</div>
      } @else if (data(); as d) {
        @if (d.items.length === 0) {
          <div class="card empty">@if (filtered()) { Không có đơn nào phù hợp bộ lọc. } @else { Hiện chưa có đơn nào đang tìm tài xế. Hãy quay lại sau. }</div>
        } @else {
          <div class="cards">
            @for (row of d.items; track row.shipment.id) {
              <a class="card offer" [routerLink]="['/driver/marketplace', row.shipment.id]" data-testid="marketplace-item">
                <div class="offer-head"><strong>{{ row.shipment.shipmentCode }}</strong>
                  @if (row.myApplicationStatus) { <span [class]="badge(row.myApplicationStatus)">{{ statusLabels[row.myApplicationStatus] }}</span> }</div>
                <div class="route"><span>↑ {{ area(row.shipment.pickup) }}</span>
                  <span>↓ {{ area(row.shipment.delivery) }}@if (row.shipment.deliveryStopCount > 1) { <em class="muted"> (+{{ row.shipment.deliveryStopCount - 1 }} điểm)</em> }</span></div>
                <div class="price">{{ vnd(row.shipment.price) }}</div>
                <dl class="facts">
                  <dt>Lấy hàng</dt><dd>{{ row.shipment.pickupTime | date: 'HH:mm dd/MM' }}</dd>
                  <dt>Hàng</dt><dd>{{ row.shipment.totalQuantity }} kiện · {{ kg(row.shipment.totalWeightKg) }}</dd>
                  <dt>Xe</dt><dd>{{ row.shipment.vehicleTypeName || 'Không yêu cầu' }}@if (row.shipment.bodyTypeCode && row.shipment.bodyTypeCode !== 'ANY') { · {{ body(row.shipment.bodyTypeCode) }} }</dd>
                  @if (row.shipment.vehicleCount > 1) { <dt>Số xe</dt><dd data-testid="vehicle-progress">{{ progress(row.shipment.vehicleCount, row.shipment.assignedVehicleCount) }}</dd> }
                  <dt>Quãng đường</dt><dd>{{ km(row.shipment.distanceKm) }}</dd>
                  <dt>Nhận đến</dt><dd>{{ row.shipment.applicationDeadline ? (row.shipment.applicationDeadline | date: 'HH:mm dd/MM') : '—' }}</dd>
                </dl>
              </a>
            }
          </div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }} · {{ d.totalElements }} đơn</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
  </main>`,
  styles: [`.filters{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:0 12px;align-items:end}.filters .wide{grid-column:span 2}.filter-actions{display:flex;gap:8px;grid-column:1/-1}
  .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px}
  .offer{display:flex;flex-direction:column;gap:8px;margin:0;color:inherit;text-decoration:none;transition:border-color .15s}.offer:hover,.offer:focus-visible{border-color:#8eb85a}
  .offer-head{display:flex;justify-content:space-between;align-items:center;gap:8px}.route span{display:block;font-size:14px}
  .price{font-size:20px;font-weight:800;color:var(--tg-dark)}.facts{display:grid;grid-template-columns:96px minmax(0,1fr);gap:2px 8px;margin:0;font-size:13px}.facts dt{color:var(--tg-muted)}.facts dd{margin:0}
  @media(max-width:900px){.filters{grid-template-columns:1fr 1fr}}@media(max-width:560px){.filters{grid-template-columns:minmax(0,1fr)}.filters .wide{grid-column:auto}}`],
})
export class DriverMarketplaceComponent implements OnInit {
  private readonly api = inject(DriverMarketplaceApi);
  private readonly catalog = inject(CatalogApi);
  protected readonly data = signal<PageResponse<MarketplaceListItem> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly filtered = signal(false);
  protected readonly vehicleTypes = signal<{ code: string; name: string }[]>([]);
  protected readonly statusLabels = APPLICATION_STATUS_LABELS;
  protected readonly filters = inject(FormBuilder).group({
    keyword: '', pickupProvince: '', pickupDistrict: '', deliveryProvince: '', deliveryDistrict: '', vehicleTypeId: '',
    minPrice: null as number | null, maxPrice: null as number | null, minWeight: null as number | null, maxWeight: null as number | null,
    maxDistance: null as number | null, sort: 'pickupTime:asc',
  });
  protected readonly area = areaText;
  protected readonly vnd = formatVnd;
  protected readonly kg = formatKg;
  protected readonly km = formatKm;
  protected readonly badge = applicationBadge;
  protected readonly body = bodyTypeLabel;
  protected readonly progress = vehicleProgress;

  ngOnInit(): void {
    this.catalog.vehicleTypes().subscribe({ next: types => this.vehicleTypes.set(types), error: () => undefined });
    this.load(0);
  }

  reset(): void { this.filters.reset({ sort: 'pickupTime:asc' }); this.load(0); }

  load(page: number): void {
    const f = this.filters.getRawValue();
    const [sortBy, sortDirection] = (f.sort ?? 'pickupTime:asc').split(':') as [MarketplaceSort, 'asc' | 'desc'];
    const query: MarketplaceQuery = {
      keyword: f.keyword ?? '', pickupProvince: f.pickupProvince ?? '', pickupDistrict: f.pickupDistrict ?? '', deliveryProvince: f.deliveryProvince ?? '',
      deliveryDistrict: f.deliveryDistrict ?? '', vehicleTypeId: f.vehicleTypeId ?? '', minPrice: f.minPrice, maxPrice: f.maxPrice, minWeight: f.minWeight,
      maxWeight: f.maxWeight, maxDistance: f.maxDistance, sortBy, sortDirection, page: Math.max(page, 0), size: 12,
    };
    this.filtered.set(Object.entries(query).some(([k, v]) => !['sortBy', 'sortDirection', 'page', 'size'].includes(k) && v !== null && v !== ''));
    this.loading.set(true);
    this.error.set(null);
    this.api.list(query).subscribe({
      next: data => { this.data.set(data); this.page.set(data.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }
}
