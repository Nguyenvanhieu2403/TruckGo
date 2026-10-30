import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { AdminApi, AdminVehicle, VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { PageResponse, toApiError } from '../../core/api.service';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { ReviewActionsComponent, ReviewDecision } from './review-actions.component';

@Component({
  selector: 'app-admin-vehicles',
  standalone: true,
  imports: [DatePipe, DecimalPipe, StatusBadgeComponent, ReviewActionsComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Phương tiện chờ duyệt</h1></div>
      <button class="btn btn-ghost" type="button" (click)="load(page())" [disabled]="loading()">Tải lại</button></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (notice()) { <div class="alert alert-success" role="status">{{ notice() }}</div> }
    <section class="card">
      @if (loading()) { <p class="muted">Đang tải…</p> }
      @else if (!data() || data()!.items.length === 0) { <div class="empty">Không có phương tiện nào đang chờ duyệt.</div> }
      @else {
        <div class="table-wrap"><table class="table">
          <thead><tr><th>Biển số</th><th>Thông số</th><th>Tài xế</th><th>Hạn đăng kiểm</th><th>Thao tác</th></tr></thead>
          <tbody>
            @for (row of data()!.items; track row.vehicle.id) {
              <tr>
                <td><strong>{{ row.vehicle.plateNumber }}</strong><br><span class="muted">{{ typeLabels[row.vehicle.vehicleType] }}</span></td>
                <td>{{ row.vehicle.maxPayloadKg | number }} kg<br><span class="muted">{{ row.vehicle.lengthCm }}×{{ row.vehicle.widthCm }}×{{ row.vehicle.heightCm }} cm</span></td>
                <td>{{ row.driverName || '—' }}<br>@if (row.driverVerificationStatus) { <app-status-badge [status]="row.driverVerificationStatus" /> }</td>
                <td>{{ row.vehicle.registrationExpiryDate | date: 'dd/MM/yyyy' }}</td>
                <td><app-review-actions [subject]="row.vehicle.plateNumber" [busy]="busyId() === row.vehicle.id" (decided)="decide(row, $event)" /></td>
              </tr>
            }
          </tbody>
        </table></div>
        <div class="pager">
          <button class="btn btn-ghost" type="button" (click)="load(page() - 1)" [disabled]="page() === 0">‹ Trước</button>
          <span>Trang {{ page() + 1 }} / {{ data()!.totalPages || 1 }} · {{ data()!.totalElements }} phương tiện</span>
          <button class="btn btn-ghost" type="button" (click)="load(page() + 1)" [disabled]="page() + 1 >= data()!.totalPages">Sau ›</button>
        </div>
      }
    </section>
  </main>`,
})
export class AdminVehiclesComponent implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly typeLabels = VEHICLE_TYPE_LABELS;
  protected readonly data = signal<PageResponse<AdminVehicle> | null>(null);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly busyId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  ngOnInit(): void { this.load(0); }

  load(page: number): void {
    this.loading.set(true);
    this.api.pendingVehicles(Math.max(page, 0)).subscribe({
      next: data => { this.data.set(data); this.page.set(data.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  decide(row: AdminVehicle, decision: ReviewDecision): void {
    this.busyId.set(row.vehicle.id);
    this.error.set(null);
    this.notice.set(null);
    this.api.decideVehicle(row.vehicle.id, decision.approve, decision.reason).subscribe({
      next: () => { this.notice.set(`${decision.approve ? 'Đã duyệt' : 'Đã từ chối'} phương tiện ${row.vehicle.plateNumber}.`); this.busyId.set(null); this.load(this.page()); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.busyId.set(null); },
    });
  }
}
