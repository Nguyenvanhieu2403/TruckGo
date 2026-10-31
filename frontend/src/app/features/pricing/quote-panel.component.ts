import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { Quote, QuoteApi } from '../../core/api/pricing.api';
import { toApiError } from '../../core/api.service';
import { formatVnd } from '../../shared/money';
import { ToastService } from '../../shared/toast.service';
import { BREAKDOWN_KEYS, COMPONENT_LABELS, QUOTE_STATUS_LABELS } from './quote-labels';

type Confirm = 'accept' | 'reject' | null;

/**
 * Quote of one order. Shows only what the API returns: amounts are never computed here, and nothing is shown as a
 * price when the API fails or the quote is not priced.
 */
@Component({
  selector: 'app-quote-panel',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
  <section class="card quote" aria-labelledby="quote-title">
    <div class="quote-head">
      <h2 id="quote-title">Báo giá</h2>
      @if (quote(); as q) { <span class="badge" [class]="'badge quote-status quote-status--' + q.status.toLowerCase()">{{ statusLabel(q.status) }}</span> }
    </div>

    @if (loading()) { <p class="muted" aria-busy="true">Đang tải báo giá…</p> }
    @else if (loadError()) {
      <div class="alert alert-error" role="alert">Không tải được báo giá: {{ loadError() }}
        <button class="btn btn-ghost" type="button" (click)="load()">Thử lại</button></div>
    } @else {
      @if (quote(); as q) {
        <p class="muted small">{{ q.quoteCode }} · phiên bản {{ q.quoteVersion }} · tạo lúc {{ q.createdAt | date: 'HH:mm dd/MM/yyyy' }}</p>
        <dl class="dl facts">
          <dt>Loại xe</dt><dd>{{ q.vehicleTypeName || 'Chưa xác định' }}@if (q.vehicleCount > 1) { × {{ q.vehicleCount }} }</dd>
          <dt>Quãng đường</dt><dd>@if (q.distanceKm !== null) { {{ q.distanceKm | number: '1.0-2' }} km
            <span class="muted">({{ q.distanceSource === 'MANUAL' ? 'đã được quản trị viên xác minh' : 'theo bản đồ' }})</span> } @else { Chưa xác định }</dd>
          <dt>Khối lượng / thể tích</dt><dd>{{ q.totalWeightKg | number: '1.0-2' }} kg · {{ q.totalVolumeM3 | number: '1.0-3' }} m³</dd>
        </dl>

        @if (q.status === 'AWAITING_MANUAL_REVIEW') {
          <div class="alert alert-warn">Chưa thể tính giá tự động: {{ q.reviewReason }}. Quản trị viên sẽ xác nhận và cập nhật báo giá; bạn không cần thao tác thêm.</div>
        }
        @if (q.breakdown; as b) {
          <table class="breakdown">
            <tbody>
              @for (row of rows(); track row.label) { <tr><td>{{ row.label }}</td><td class="num">{{ vnd(row.amount) }}</td></tr> }
            </tbody>
            <tfoot><tr><th>Tổng giá</th><th class="num total">{{ vnd(q.totalPrice) }}</th></tr></tfoot>
          </table>
          <details class="lines"><summary>Chi tiết từng khoản ({{ q.lines.length }})</summary>
            <ul class="line-list">
              @for (l of q.lines; track l.lineNo) {
                <li><div class="line-main"><span>{{ l.name }}</span><span class="num">{{ vnd(l.amount) }}</span></div>
                  <div class="line-sub muted">@if (l.description) { {{ l.description }} · }{{ l.quantity | number: '1.0-3' }} × {{ vnd(l.unitPrice) }}</div></li>
              }
            </ul>
          </details>
          @if (q.adjustmentReason) { <p class="small muted">Điều chỉnh của quản trị viên: {{ q.adjustmentReason }}</p> }
        }
        @switch (q.status) {
          @case ('CALCULATED') { <p class="small">Báo giá có hiệu lực đến <strong>{{ q.expiresAt | date: 'HH:mm dd/MM/yyyy' }}</strong>.</p> }
          @case ('ACCEPTED') { <div class="alert alert-success">Bạn đã xác nhận báo giá lúc {{ q.acceptedAt | date: 'HH:mm dd/MM/yyyy' }}.</div> }
          @case ('REJECTED') { <div class="alert alert-info">Bạn đã từ chối báo giá@if (q.rejectionReason) {: {{ q.rejectionReason }}}. Có thể yêu cầu tính lại giá.</div> }
          @case ('EXPIRED') { <div class="alert alert-info">Báo giá đã hết hạn. Vui lòng tính lại giá.</div> }
          @case ('SUPERSEDED') { <div class="alert alert-info">Đơn đã thay đổi nên báo giá này không còn hiệu lực. Vui lòng tính lại giá.</div> }
        }
      } @else {
        <p class="muted">Đơn chưa có báo giá. Hệ thống tính giá từ quãng đường, loại xe, hàng hóa và dịch vụ của đơn.</p>
      }

      @if (confirming() === 'accept') {
        <div class="confirm" role="dialog" aria-label="Xác nhận báo giá">
          <p>Xác nhận báo giá <strong>{{ vnd(quote()?.totalPrice) }}</strong>? Sau khi xác nhận, báo giá không thể thay đổi.</p>
          <div class="actions-row"><button class="btn btn-primary" type="button" (click)="accept()" [disabled]="busy()">{{ busy() ? 'Đang xử lý…' : 'Xác nhận' }}</button>
            <button class="btn btn-ghost" type="button" (click)="confirming.set(null)" [disabled]="busy()">Quay lại</button></div>
        </div>
      } @else if (confirming() === 'reject') {
        <div class="confirm" role="dialog" aria-label="Từ chối báo giá">
          <div class="field"><label for="reject-reason">Lý do từ chối (không bắt buộc)</label>
            <textarea id="reject-reason" rows="2" maxlength="500" [(ngModel)]="rejectReason"></textarea></div>
          <div class="actions-row"><button class="btn btn-danger" type="button" (click)="reject()" [disabled]="busy()">{{ busy() ? 'Đang xử lý…' : 'Từ chối báo giá' }}</button>
            <button class="btn btn-ghost" type="button" (click)="confirming.set(null)" [disabled]="busy()">Quay lại</button></div>
        </div>
      } @else if (owner()) {
        <div class="actions-row">
          @if (quote()?.actions?.acceptable) {
            <button class="btn btn-primary" type="button" (click)="confirming.set('accept')">Xác nhận báo giá</button>
            <button class="btn btn-ghost" type="button" (click)="confirming.set('reject')">Từ chối báo giá</button>
          }
          @if (canRequest()) {
            <button class="btn" [class.btn-primary]="!quote()" [class.btn-ghost]="!!quote()" type="button" (click)="requestQuote()" [disabled]="busy()">
              {{ busy() ? 'Đang tính giá…' : quote() ? 'Tính lại giá' : 'Tính giá' }}</button>
          }
        </div>
      }
      @if (error()) { <div class="alert alert-error" role="alert" style="margin-top:12px">{{ error() }}</div> }

      @if (quote()) {
        <details class="history" (toggle)="loadHistory($event)"><summary>Lịch sử báo giá</summary>
          @for (h of history(); track h.id) {
            <div class="history-row"><span>{{ h.quoteCode }}</span><span>{{ statusLabel(h.status) }}</span><span>{{ vnd(h.totalPrice) }}</span>
              <span class="muted">{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }}</span></div>
          } @empty { <p class="muted small">Đang tải…</p> }
        </details>
      }
    }
  </section>`,
  styles: [`.quote-head{display:flex;justify-content:space-between;align-items:center;gap:8px}.quote-head h2{margin:0}.small{font-size:12px}.facts{margin:12px 0}
  .breakdown{width:100%;border-collapse:collapse;margin:8px 0 6px;font-size:14px}.breakdown td,.breakdown th{padding:7px 0;border-bottom:1px solid #eef1ea;text-align:left}
  .breakdown tfoot th{border-top:2px solid var(--tg-ink);border-bottom:0;font-size:15px}.num{text-align:right!important;white-space:nowrap}.total{font-size:18px}
  .lines,.history{margin:10px 0;font-size:13px}.line-list{list-style:none;padding:0;margin:8px 0 0}.line-list li{padding:7px 0;border-bottom:1px solid #eef1ea}
  .line-main{display:flex;justify-content:space-between;gap:12px}.line-sub{font-size:12px;margin-top:2px}.lines summary,.history summary{cursor:pointer;color:var(--tg-muted)}
  .history-row{display:grid;grid-template-columns:1.4fr 1fr 1fr 1fr;gap:8px;padding:6px 0;border-bottom:1px solid #eef1ea}
  @media(max-width:560px){.history-row{grid-template-columns:1fr 1fr}}`],
})
export class QuotePanelComponent implements OnInit {
  readonly shipmentId = input.required<string>();
  /** Customer who owns the order (admins see the panel read-only). */
  readonly owner = input(false);
  /** Order is DRAFT or PUBLISHED, i.e. can be priced. */
  readonly shipmentOpen = input(false);
  readonly changed = output<Quote>();
  private readonly api = inject(QuoteApi);
  private readonly toast = inject(ToastService);
  protected readonly quote = signal<Quote | null>(null);
  protected readonly history = signal<Quote[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirming = signal<Confirm>(null);
  protected rejectReason = '';
  protected readonly vnd = formatVnd;
  protected readonly rows = computed(() => {
    const b = this.quote()?.breakdown;
    return b ? BREAKDOWN_KEYS.filter(k => k.component === 'BASE' || k.component === 'DISTANCE' || b[k.key] !== 0)
      .map(k => ({ label: COMPONENT_LABELS[k.component], amount: b[k.key] })) : [];
  });
  protected readonly canRequest = computed(() => {
    const q = this.quote();
    return this.owner() && this.shipmentOpen() && (!q || q.actions.recalculable);
  });

  ngOnInit(): void { this.load(); }

  statusLabel(status: Quote['status']): string { return QUOTE_STATUS_LABELS[status]; }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.current(this.shipmentId()).subscribe({
      next: q => { this.quote.set(q); this.loading.set(false); },
      error: (e: unknown) => { this.loadError.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  loadHistory(event: Event): void {
    if ((event.target as HTMLDetailsElement).open) this.api.history(this.shipmentId()).subscribe({ next: h => this.history.set(h), error: () => this.history.set([]) });
  }

  requestQuote(): void {
    this.run(this.api.preview(this.shipmentId()), q => q.status === 'CALCULATED' ? 'Đã tính giá cho đơn hàng.' : 'Đơn cần quản trị viên xác nhận trước khi báo giá.');
  }

  accept(): void { this.run(this.api.accept(this.shipmentId()), () => 'Đã xác nhận báo giá.'); }

  reject(): void { this.run(this.api.reject(this.shipmentId(), this.rejectReason.trim() || null), () => 'Đã từ chối báo giá.'); }

  private run(request: Observable<Quote>, message: (q: Quote) => string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: q => {
        this.quote.set(q);
        this.history.set([]);
        this.confirming.set(null);
        this.rejectReason = '';
        this.busy.set(false);
        this.toast.success(message(q));
        this.changed.emit(q);
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        const detail = Object.values(err.fieldErrors).join(' ');
        this.error.set(detail ? `${err.message}: ${detail}` : err.message);
        this.toast.error(err.message);
        this.busy.set(false);
        this.confirming.set(null);
        if (err.status === 409) this.load(); // state changed elsewhere (expired, superseded…)
      },
    });
  }
}
