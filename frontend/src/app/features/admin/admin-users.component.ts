import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdminApi } from '../../core/api/account.api';
import { PageResponse, toApiError } from '../../core/api.service';
import { User, UserRole, UserStatus } from '../../core/auth/auth.models';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [DatePipe, FormsModule],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Người dùng</h1></div></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    <section class="card">
      <div class="grid-3">
        <div class="field"><label for="role">Vai trò</label>
          <select id="role" [(ngModel)]="role" (ngModelChange)="load(0)"><option value="">Tất cả</option><option value="CUSTOMER">Khách hàng</option><option value="DRIVER">Tài xế</option><option value="ADMIN">Quản trị</option></select></div>
        <div class="field"><label for="status">Trạng thái</label>
          <select id="status" [(ngModel)]="status" (ngModelChange)="load(0)"><option value="">Tất cả</option><option value="ACTIVE">Hoạt động</option><option value="SUSPENDED">Tạm ngưng</option><option value="LOCKED">Đã khóa</option></select></div>
      </div>
      @if (data(); as d) {
        @if (d.items.length === 0) { <div class="empty">Không có người dùng phù hợp.</div> }
        @else {
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Họ tên</th><th>Liên hệ</th><th>Vai trò</th><th>Trạng thái</th><th>Tạo lúc</th></tr></thead>
            <tbody>@for (u of d.items; track u.id) {
              <tr><td>{{ u.fullName }}</td><td>{{ u.email || '—' }}<br><span class="muted">{{ u.phone || '' }}</span></td><td>{{ u.role }}</td><td>{{ u.status }}</td><td>{{ u.createdAt | date: 'dd/MM/yyyy' }}</td></tr>
            }</tbody>
          </table></div>
          <div class="pager">
            <button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }}</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button>
          </div>
        }
      } @else { <p class="muted">Đang tải…</p> }
    </section>
  </main>`,
})
export class AdminUsersComponent implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly data = signal<PageResponse<User> | null>(null);
  protected readonly error = signal<string | null>(null);
  protected role: UserRole | '' = '';
  protected status: UserStatus | '' = '';

  ngOnInit(): void { this.load(0); }

  load(page: number): void {
    this.api.users({ role: this.role || undefined, status: this.status || undefined, page: Math.max(page, 0) }).subscribe({
      next: data => this.data.set(data),
      error: (e: unknown) => this.error.set(toApiError(e).message),
    });
  }
}
