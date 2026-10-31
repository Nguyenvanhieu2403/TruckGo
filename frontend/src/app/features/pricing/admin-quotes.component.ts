import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AdminPricingApi, AdminQuote } from '../../core/api/pricing.api';
import { PageResponse, toApiError } from '../../core/api.service';
import { formatVnd } from '../../shared/money';
import { applyServerErrors, fieldError } from '../../shared/form-utils';
import { ToastService } from '../../shared/toast.service';

/** Quotes the system could not price on its own (no distance, no price table, vehicle too small). */
@Component({
  selector: 'app-admin-quotes',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ReactiveFormsModule, RouterLink],
  template: `
  <main class="container">
    <div class="page-head"><div><div class="kicker">Quản trị</div><h1>Báo giá chờ xử lý</h1></div>
      <button class="btn btn-ghost" type="button" (click)="load(page())" [disabled]="loading()">Tải lại</button></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    <section class="card">
      @if (loading()) { <p class="muted" aria-busy="true">Đang tải…</p> }
      @else if (data(); as d) {
        @if (d.items.length === 0) { <div class="empty">Không có báo giá nào cần xử lý.</div> }
        @else {
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Báo giá</th><th>Lộ trình</th><th>Loại xe</th><th>Lý do</th><th>Tạo lúc</th><th></th></tr></thead>
            <tbody>@for (a of d.items; track a.quote.id) {
              <tr [class.selected]="selected()?.quote?.id === a.quote.id">
                <td><strong>{{ a.quote.quoteCode }}</strong><br><a [routerLink]="['/admin/shipments', a.quote.shipmentId]">Xem đơn</a></td>
                <td>↑ {{ a.pickupAddress }}<br>↓ {{ a.deliveryAddress }} <span class="muted">({{ a.quote.stopCount }} điểm)</span></td>
                <td>{{ a.quote.vehicleTypeName || '—' }}<br><span class="muted">{{ a.quote.totalWeightKg | number: '1.0-2' }} kg</span></td>
                <td>{{ a.quote.reviewReason }}</td>
                <td>{{ a.quote.createdAt | date: 'HH:mm dd/MM' }}</td>
                <td><button class="btn btn-ghost" type="button" (click)="select(a)">Xử lý</button></td>
              </tr>
            }</tbody>
          </table></div>
          <div class="pager"><button class="btn btn-ghost" type="button" (click)="load(d.page - 1)" [disabled]="d.page === 0">‹ Trước</button>
            <span>Trang {{ d.page + 1 }} / {{ d.totalPages || 1 }}</span>
            <button class="btn btn-ghost" type="button" (click)="load(d.page + 1)" [disabled]="d.page + 1 >= d.totalPages">Sau ›</button></div>
        }
      }
    </section>
    @if (selected(); as s) {
      <section class="card" aria-labelledby="adjust-title">
        <h2 id="adjust-title">Xử lý {{ s.quote.quoteCode }}</h2>
        <p class="muted">{{ s.quote.reviewReason }}</p>
        <form [formGroup]="form" (ngSubmit)="adjust(s)" novalidate>
          <div class="grid-3">
            <div class="field" [class.invalid]="err('distanceKm')"><label for="distanceKm">Quãng đường đã xác minh (km)</label>
              <input id="distanceKm" type="number" min="0.01" max="5000" step="0.01" formControlName="distanceKm" />
              @if (err('distanceKm'); as e) { <span class="error">{{ e }}</span> } @else { <span class="hint">Theo tuyến đường thực tế qua các điểm dừng</span> }</div>
            <div class="field" [class.invalid]="err('adjustmentAmount')"><label for="adjustmentAmount">Điều chỉnh (VNĐ, có thể âm)</label>
              <input id="adjustmentAmount" type="number" step="1000" formControlName="adjustmentAmount" />
              @if (err('adjustmentAmount'); as e) { <span class="error">{{ e }}</span> }</div>
          </div>
          <div class="field" [class.invalid]="err('reason')"><label for="reason">Lý do (bắt buộc, được lưu vào nhật ký)</label>
            <textarea id="reason" rows="2" maxlength="500" formControlName="reason"></textarea>
            @if (err('reason'); as e) { <span class="error">{{ e }}</span> }</div>
          @if (formError()) { <div class="alert alert-error" role="alert">{{ formError() }}</div> }
          <div class="actions-row">
            <button class="btn btn-primary" type="submit" [disabled]="busy()">{{ busy() ? 'Đang xử lý…' : 'Tạo báo giá đã điều chỉnh' }}</button>
            <button class="btn btn-ghost" type="button" (click)="recalculate(s)" [disabled]="busy()">Tính lại theo bảng giá hiện hành</button>
            <button class="btn btn-ghost" type="button" (click)="selected.set(null)" [disabled]="busy()">Đóng</button>
          </div>
        </form>
      </section>
    }
  </main>`,
  styles: [`tr.selected{background:#f4f7ef}`],
})
export class AdminQuotesComponent implements OnInit {
  private readonly api = inject(AdminPricingApi);
  private readonly toast = inject(ToastService);
  protected readonly data = signal<PageResponse<AdminQuote> | null>(null);
  protected readonly selected = signal<AdminQuote | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly page = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly vnd = formatVnd;
  protected readonly form = new FormGroup({
    distanceKm: new FormControl<number | null>(null, { validators: [Validators.min(0.01), Validators.max(5000)] }),
    adjustmentAmount: new FormControl<number | null>(null, { validators: [Validators.min(-1_000_000_000), Validators.max(1_000_000_000)] }),
    reason: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(5), Validators.maxLength(500)] }),
  });

  ngOnInit(): void { this.load(0); }

  err(name: 'distanceKm' | 'adjustmentAmount' | 'reason'): string | null {
    return fieldError(this.form.controls[name], { required: 'Bắt buộc', minlength: 'Lý do dài 5–500 ký tự', min: 'Giá trị không hợp lệ', max: 'Giá trị không hợp lệ' });
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.pendingQuotes(Math.max(page, 0)).subscribe({
      next: d => { this.data.set(d); this.page.set(d.page); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  select(a: AdminQuote): void {
    this.selected.set(a);
    this.formError.set(null);
    this.form.reset({ distanceKm: a.quote.distanceKm, adjustmentAmount: null, reason: '' });
  }

  adjust(a: AdminQuote): void {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    const v = this.form.getRawValue();
    if (this.form.invalid) return;
    if (v.distanceKm === null && v.adjustmentAmount === null) { this.formError.set('Nhập quãng đường hoặc khoản điều chỉnh.'); return; }
    this.run(this.api.adjust(a.quote.id, { distanceKm: v.distanceKm, adjustmentAmount: v.adjustmentAmount, reason: v.reason.trim() }));
  }

  recalculate(a: AdminQuote): void { this.run(this.api.recalculate(a.quote.id)); }

  private run(request: Observable<AdminQuote>): void {
    this.busy.set(true);
    this.formError.set(null);
    request.subscribe({
      next: r => {
        this.busy.set(false);
        this.toast.success(r.quote.status === 'CALCULATED' ? `Đã tạo ${r.quote.quoteCode}: ${formatVnd(r.quote.totalPrice)}` : `${r.quote.quoteCode} vẫn cần xử lý: ${r.quote.reviewReason}`);
        this.selected.set(null);
        this.load(this.page());
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        const unmatched = applyServerErrors(this.form, err.fieldErrors);
        this.formError.set(unmatched.join(' ') || err.message);
        this.busy.set(false);
      },
    });
  }
}
