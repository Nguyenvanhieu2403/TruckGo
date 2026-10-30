import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AdminApi, AdminDriver } from '../../core/api/account.api';
import { PageResponse, toApiError } from '../../core/api.service';
import { ReviewActionsComponent, ReviewDecision } from './review-actions.component';

@Component({
  selector: 'app-admin-drivers',
  standalone: true,
  imports: [DatePipe, ReviewActionsComponent],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Hồ sơ tài xế chờ duyệt</h1></div>
      <button class="btn btn-ghost" type="button" (click)="load(page())" [disabled]="loading()">Tải lại</button></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (notice()) { <div class="alert alert-success" role="status">{{ notice() }}</div> }
    <section class="card">
      @if (loading()) { <p class="muted">Đang tải…</p> }
      @else if (!data() || data()!.items.length === 0) { <div class="empty">Không có hồ sơ nào đang chờ duyệt.</div> }
      @else {
        <div class="table-wrap"><table class="table">
          <thead><tr><th>Tài xế</th><th>Liên hệ</th><th>Giấy phép</th><th>Gửi lúc</th><th>Thao tác</th></tr></thead>
          <tbody>
            @for (d of data()!.items; track d.profile.id) {
              <tr>
                <td><strong>{{ d.fullName }}</strong></td>
                <td>{{ d.email || '—' }}<br><span class="muted">{{ d.phone || '' }}</span></td>
                <td>{{ d.profile.licenseNumber }}<br><span class="muted">Hạng {{ d.profile.licenseClass }}</span></td>
                <td>{{ d.profile.submittedAt | date: 'dd/MM/yyyy HH:mm' }}</td>
                <td><app-review-actions [subject]="d.fullName ?? d.profile.id" [busy]="busyId() === d.profile.id" (decided)="decide(d, $event)" /></td>
              </tr>
            }
          </tbody>
        </table></div>
        <div class="pager">
          <button class="btn btn-ghost" type="button" (click)="load(page() - 1)" [disabled]="page() === 0">‹ Trước</button>
          <span>Trang {{ page() + 1 }} / {{ data()!.totalPages || 1 }} · {{ data()!.totalElements }} hồ sơ</span>
          <button class="btn btn-ghost" type="button" (click)="load(page() + 1)" [disabled]="page() + 1 >= data()!.totalPages">Sau ›</button>
        </div>
      }
    </section>
  </main>`,
})
export class AdminDriversComponent implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly data = signal<PageResponse<AdminDriver> | null>(null);
  protected readonly page = signal(0);
  protected readonly loading = signal(true);
  protected readonly busyId = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  ngOnInit(): void { this.load(0); }

  load(page: number): void {
    this.loading.set(true);
    this.api.pendingDrivers(Math.max(page, 0)).subscribe({
      next: data => { this.data.set(data); this.page.set(data.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  /** The row is removed only after the backend confirms; a 403/409 leaves the list untouched. */
  decide(item: AdminDriver, decision: ReviewDecision): void {
    this.busyId.set(item.profile.id);
    this.error.set(null);
    this.notice.set(null);
    this.api.decideDriver(item.profile.id, decision.approve, decision.reason).subscribe({
      next: () => {
        this.notice.set(`${decision.approve ? 'Đã duyệt' : 'Đã từ chối'} hồ sơ của ${item.fullName ?? 'tài xế'}.`);
        this.busyId.set(null);
        this.load(this.page());
      },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.busyId.set(null); },
    });
  }
}
