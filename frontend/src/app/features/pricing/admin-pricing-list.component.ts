import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CatalogApi, CatalogVehicleType } from '../../core/api/catalog.api';
import { AdminPricingApi, PricingConfig } from '../../core/api/pricing.api';
import { PageResponse, toApiError } from '../../core/api.service';
import { formatVnd } from '../../shared/money';
import { ToastService } from '../../shared/toast.service';

@Component({
  selector: 'app-admin-pricing-list',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Bảng giá</h1></div>
      <a class="btn btn-primary" routerLink="/admin/pricing/new">+ Tạo bảng giá</a></div>
    <section class="card">
      <div class="grid-3">
        <div class="field"><label for="vehicle">Loại xe</label>
          <select id="vehicle" [(ngModel)]="vehicle" (ngModelChange)="load(0)"><option value="">Tất cả</option>
            @for (v of vehicleTypes(); track v.code) { <option [value]="v.code">{{ v.name }}</option> }</select></div>
        <div class="field"><label for="active">Trạng thái</label>
          <select id="active" [(ngModel)]="active" (ngModelChange)="load(0)"><option value="">Tất cả</option><option value="true">Đang bật</option><option value="false">Đã tắt</option></select></div>
      </div>
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }} <button class="btn btn-ghost" type="button" (click)="load(page())">Thử lại</button></div> }
      @if (loading()) { <p class="muted" aria-busy="true">Đang tải…</p> }
      @else if (data(); as d) {
        @if (d.items.length === 0) { <div class="empty">Chưa có bảng giá nào phù hợp. Khi chưa có bảng giá, báo giá của khách sẽ chờ quản trị viên xử lý.</div> }
        @else {
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Tên</th><th>Loại xe</th><th>Giá mở cửa</th><th>Giá/km</th><th>Hiệu lực</th><th>Trạng thái</th><th></th></tr></thead>
            <tbody>@for (c of d.items; track c.id) {
              <tr>
                <td><a [routerLink]="['/admin/pricing', c.id]"><strong>{{ c.name }}</strong></a></td>
                <td>{{ c.vehicleTypeName || c.vehicleTypeCode }}</td>
                <td>{{ vnd(c.basePrice) }}</td><td>{{ vnd(c.pricePerKm) }}</td>
                <td>{{ c.effectiveFrom | date: 'dd/MM/yyyy HH:mm' }} → {{ c.effectiveTo ? (c.effectiveTo | date: 'dd/MM/yyyy HH:mm') : 'không thời hạn' }}</td>
                <td>@if (!c.active) { <span class="badge">Đã tắt</span> } @else if (c.effectiveNow) { <span class="badge badge--approved">Đang áp dụng</span> }
                  @else { <span class="badge badge--pending_review">Bật, ngoài thời gian hiệu lực</span> }</td>
                <td><div class="actions-row">
                  <a class="btn btn-ghost" [routerLink]="['/admin/pricing', c.id]">Sửa</a>
                  @if (confirmId() === c.id) {
                    <button class="btn" [class.btn-danger]="c.active" [class.btn-primary]="!c.active" type="button" (click)="toggle(c)" [disabled]="busy()">Xác nhận {{ c.active ? 'tắt' : 'bật' }}</button>
                    <button class="btn btn-ghost" type="button" (click)="confirmId.set(null)">Hủy</button>
                  } @else { <button class="btn btn-ghost" type="button" (click)="confirmId.set(c.id)">{{ c.active ? 'Tắt' : 'Bật' }}</button> }
                </div></td>
              </tr>
            }</tbody>
          </table></div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }}</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
  </main>`,
})
export class AdminPricingListComponent implements OnInit {
  private readonly api = inject(AdminPricingApi);
  private readonly catalogs = inject(CatalogApi);
  private readonly toast = inject(ToastService);
  protected readonly vehicleTypes = signal<CatalogVehicleType[]>([]);
  protected readonly data = signal<PageResponse<PricingConfig> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly page = signal(0);
  protected readonly confirmId = signal<string | null>(null);
  protected readonly vnd = formatVnd;
  protected vehicle = '';
  protected active = '';

  ngOnInit(): void {
    this.catalogs.vehicleTypes().subscribe({ next: v => this.vehicleTypes.set(v), error: () => undefined });
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list({ vehicleTypeCode: this.vehicle || undefined, active: this.active === '' ? null : this.active === 'true', page: Math.max(page, 0) }).subscribe({
      next: d => { this.data.set(d); this.page.set(d.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  toggle(c: PricingConfig): void {
    this.busy.set(true);
    this.api.setActive(c.id, !c.active).subscribe({
      next: () => { this.toast.success(c.active ? 'Đã tắt bảng giá.' : 'Đã bật bảng giá.'); this.busy.set(false); this.confirmId.set(null); this.load(this.page()); },
      error: (e: unknown) => { const err = toApiError(e); this.toast.error(Object.values(err.fieldErrors)[0] ?? err.message); this.busy.set(false); this.confirmId.set(null); },
    });
  }
}
