import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PageResponse, toApiError } from '../../core/api.service';
import { APPLICATION_STATUS_LABELS, ApplicationStatus, DriverApplication, DriverMarketplaceApi, areaText } from '../../core/api/marketplace.api';
import { formatVnd } from '../../shared/money';
import { applicationBadge } from './marketplace-labels';

@Component({
  selector: 'app-driver-applications',
  standalone: true,
  imports: [DatePipe, RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Yêu cầu nhận đơn của tôi</h1></div>
      <a class="btn btn-primary" routerLink="/driver/marketplace">Tìm đơn mới</a></div>
    <div class="segmented" role="tablist" aria-label="Lọc theo trạng thái">
      @for (t of tabs; track t.value) {
        <button type="button" role="tab" [attr.aria-selected]="status() === t.value" [class.active]="status() === t.value" (click)="select(t.value)">{{ t.label }}</button>
      }
    </div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }} <button class="btn btn-ghost" type="button" (click)="load(page())">Thử lại</button></div> }
    <section class="card">
      @if (loading()) { <div aria-busy="true">@for (n of [1, 2, 3]; track n) { <div class="skeleton-row"></div> }</div> }
      @else if (data(); as d) {
        @if (d.items.length === 0) {
          <div class="empty">@if (status()) { Không có yêu cầu nào ở trạng thái này. } @else { Bạn chưa gửi yêu cầu nhận đơn nào. }
            <br><a class="btn btn-primary" style="margin-top:14px" routerLink="/driver/marketplace">Tìm đơn</a></div>
        } @else {
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Đơn</th><th>Lộ trình</th><th>Giá</th><th>Xe</th><th>Gửi lúc</th><th>Trạng thái</th></tr></thead>
            <tbody>@for (a of d.items; track a.id) {
              <tr data-testid="application-row">
                <td><a [routerLink]="['/driver/applications', a.id]"><strong>{{ a.shipment?.shipmentCode }}</strong></a><br>
                  <span class="muted">Lấy {{ a.shipment?.pickupTime ? (a.shipment?.pickupTime | date: 'HH:mm dd/MM') : '—' }}</span></td>
                <td class="route"><span>↑ {{ area(a.shipment?.pickup ?? null) }}</span><span>↓ {{ area(a.shipment?.delivery ?? null) }}</span></td>
                <td>{{ vnd(a.shipment?.price) }}</td><td>{{ a.vehicle?.plateNumber }}</td><td>{{ a.appliedAt | date: 'HH:mm dd/MM' }}</td>
                <td><span [class]="badge(a.status)">{{ labels[a.status] }}</span></td>
              </tr>
            }</tbody></table></div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }}</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      }
    </section>
  </main>`,
  styles: [`.segmented{margin-bottom:14px;flex-wrap:wrap}.route span{display:block;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}`],
})
export class DriverApplicationsComponent implements OnInit {
  private readonly api = inject(DriverMarketplaceApi);
  protected readonly tabs: { value: ApplicationStatus | ''; label: string }[] = [
    { value: '', label: 'Tất cả' }, { value: 'PENDING', label: 'Đang chờ' }, { value: 'ACCEPTED', label: 'Được chọn' },
    { value: 'REJECTED', label: 'Không được chọn' }, { value: 'WITHDRAWN', label: 'Đã rút' }, { value: 'EXPIRED', label: 'Đã đóng' }, { value: 'RELEASED', label: 'Bị gỡ' },
  ];
  protected readonly labels = APPLICATION_STATUS_LABELS;
  protected readonly status = signal<ApplicationStatus | ''>('');
  protected readonly data = signal<PageResponse<DriverApplication> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly area = areaText;
  protected readonly vnd = formatVnd;
  protected readonly badge = applicationBadge;

  ngOnInit(): void { this.load(0); }

  select(status: ApplicationStatus | ''): void { this.status.set(status); this.load(0); }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.mine(this.status(), Math.max(page, 0)).subscribe({
      next: data => { this.data.set(data); this.page.set(data.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }
}
