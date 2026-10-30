import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { DriverApi, DriverProfile, Vehicle, VehicleApi } from '../../core/api/account.api';
import { toApiError } from '../../core/api.service';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-driver-verification',
  standalone: true,
  imports: [RouterLink, DatePipe, StatusBadgeComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Khu vực tài xế</div><h1>Trạng thái duyệt hồ sơ</h1></div></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (profile(); as p) {
      @switch (p.verificationStatus) {
        @case ('APPROVED') { <div class="alert alert-success">Hồ sơ tài xế đã được duyệt. Bạn cần ít nhất một phương tiện đã duyệt để nhận đơn khi tính năng này ra mắt.</div> }
        @case ('PENDING_REVIEW') { <div class="alert alert-warn">Hồ sơ đang chờ quản trị viên duyệt. Bạn chưa thể nhận đơn vận chuyển.</div> }
        @case ('REJECTED') { <div class="alert alert-error">Hồ sơ bị từ chối: {{ p.rejectionReason }}. Hãy cập nhật giấy phép rồi gửi duyệt lại.</div> }
        @default { <div class="alert alert-info">Hồ sơ chưa được gửi duyệt. Kiểm tra thông tin giấy phép rồi gửi để quản trị viên xét duyệt.</div> }
      }
      <section class="card">
        <h2>Hồ sơ tài xế</h2>
        <dl class="dl">
          <dt>Trạng thái</dt><dd><app-status-badge [status]="p.verificationStatus" /></dd>
          <dt>Số giấy phép</dt><dd>{{ p.licenseNumber || '—' }}</dd>
          <dt>Hạng</dt><dd>{{ p.licenseClass || '—' }}</dd>
          <dt>Gửi duyệt lúc</dt><dd>{{ p.submittedAt ? (p.submittedAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</dd>
          <dt>Duyệt lúc</dt><dd>{{ p.reviewedAt ? (p.reviewedAt | date: 'dd/MM/yyyy HH:mm') : '—' }}</dd>
        </dl>
        <div class="actions-row" style="margin-top:18px">
          @if (p.verificationStatus === 'NOT_SUBMITTED' || p.verificationStatus === 'REJECTED') {
            <button class="btn btn-primary" type="button" (click)="submit()" [disabled]="submitting()">{{ submitting() ? 'Đang gửi…' : 'Gửi hồ sơ để duyệt' }}</button>
          }
          <a class="btn btn-ghost" routerLink="/driver/profile">Chỉnh sửa giấy phép</a>
        </div>
      </section>
      <section class="card">
        <h2>Phương tiện</h2>
        @if (vehicles().length === 0) { <p class="muted">Bạn chưa đăng ký phương tiện nào.</p> }
        @else { <p>{{ vehicles().length }} phương tiện · {{ approvedVehicles() }} đã duyệt · {{ pendingVehicles() }} chờ duyệt</p> }
        <a class="btn btn-ghost" routerLink="/driver/vehicles">Quản lý phương tiện</a>
      </section>
    } @else if (!error()) { <p class="muted">Đang tải…</p> }
  </main>`,
})
export class DriverVerificationComponent implements OnInit {
  private readonly drivers = inject(DriverApi);
  private readonly vehicleApi = inject(VehicleApi);
  protected readonly profile = signal<DriverProfile | null>(null);
  protected readonly vehicles = signal<Vehicle[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  protected readonly approvedVehicles = computed(() => this.vehicles().filter(v => v.verificationStatus === 'APPROVED').length);
  protected readonly pendingVehicles = computed(() => this.vehicles().filter(v => v.verificationStatus === 'PENDING_REVIEW').length);

  ngOnInit(): void {
    forkJoin({ profile: this.drivers.me(), vehicles: this.vehicleApi.mine() }).subscribe({
      next: ({ profile, vehicles }) => { this.profile.set(profile); this.vehicles.set(vehicles); },
      error: (e: unknown) => this.error.set(toApiError(e).message),
    });
  }

  submit(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.drivers.submit().subscribe({
      next: profile => { this.profile.set(profile); this.submitting.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.submitting.set(false); },
    });
  }
}
