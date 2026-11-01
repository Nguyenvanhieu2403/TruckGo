import { Component, OnInit, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { toApiError } from '../../core/api.service';
import { APPLICATION_STATUS_LABELS, Applicant, CustomerApplicationApi } from '../../core/api/marketplace.api';
import { VEHICLE_TYPE_LABELS } from '../../core/api/account.api';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { ToastService } from '../../shared/toast.service';
import { MARKETPLACE_ERROR_HINTS, applicationBadge, bodyTypeLabel } from './marketplace-labels';

type Pending = { kind: 'accept' | 'reject'; applicant: Applicant };

/** Customer view of drivers who applied for one order, with choose/reject actions. */
@Component({
  selector: 'app-applicants-panel',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ConfirmDialogComponent],
  template: `
  <section class="card" aria-labelledby="applicants-title">
    <div class="head"><h2 id="applicants-title">Tài xế muốn nhận đơn</h2>
      @if (vehicleCount() > 1) { <span class="badge" data-testid="applicants-progress">Đã chọn {{ assignedCount() }}/{{ vehicleCount() }} xe</span> }
      <button class="btn btn-ghost" type="button" (click)="load()" [disabled]="loading()" aria-label="Tải lại danh sách tài xế">Tải lại</button></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (loading()) { @for (n of [1, 2]; track n) { <div class="skeleton-row"></div> } }
    @else {
      @for (a of applicants(); track a.id) {
        <article class="applicant" [class.closed]="a.status !== 'PENDING' && a.status !== 'ACCEPTED'" data-testid="applicant">
          <div class="row"><strong>{{ a.driver?.fullName || 'Tài xế' }}</strong><span [class]="badge(a.status)">{{ labels[a.status] }}</span></div>
          <div class="meta">Bằng lái hạng {{ a.driver?.licenseClass || '—' }} · {{ a.driver?.completedTrips ?? 0 }} chuyến đã hoàn thành trên TruckGo</div>
          @if (a.vehicle; as v) { <div class="meta">Xe {{ v.plateNumber }} · {{ vehicleLabels[v.vehicleType] }} · {{ bodyLabel(v.bodyTypeCode) }} · {{ v.maxPayloadKg | number }} kg · thùng {{ v.lengthCm }}×{{ v.widthCm }}×{{ v.heightCm }} cm</div> }
          @if (a.driverMessage) { <p class="message">“{{ a.driverMessage }}”</p> }
          @if (a.driver?.phone) { <div class="meta">Điện thoại: <a [href]="'tel:' + a.driver?.phone">{{ a.driver?.phone }}</a></div> }
          @if (!a.vehicleEligible) { <div class="alert alert-warn">Xe không còn phù hợp: {{ a.vehicleIssues.join('; ') }}</div> }
          @if (a.responseReason && a.status !== 'ACCEPTED') { <div class="meta muted">Lý do: {{ a.responseReason }}</div> }
          <div class="meta muted">Gửi lúc {{ a.appliedAt | date: 'HH:mm dd/MM/yyyy' }}</div>
          @if (canChoose() && a.status === 'PENDING') {
            <div class="actions-row">
              <button class="btn btn-primary" type="button" (click)="ask('accept', a)" [disabled]="!a.vehicleEligible">Chọn tài xế này</button>
              <button class="btn btn-ghost" type="button" (click)="ask('reject', a)">Từ chối</button>
            </div>
          }
        </article>
      } @empty {
        <p class="empty">Chưa có tài xế nào gửi yêu cầu. Bạn sẽ nhận được thông báo khi có tài xế quan tâm.</p>
      }
    }
  </section>
  @if (pending(); as p) {
    @if (p.kind === 'accept') {
      <app-confirm-dialog [title]="'Chọn ' + (p.applicant.driver?.fullName || 'tài xế') + '?'" confirmLabel="Xác nhận chọn" [busy]="busy()" [error]="dialogError()"
        [message]="acceptMessage()"
        (confirmed)="confirm(p, null)" (closed)="pending.set(null)" />
    } @else {
      <app-confirm-dialog [title]="'Từ chối ' + (p.applicant.driver?.fullName || 'tài xế') + '?'" confirmLabel="Từ chối" [danger]="true" [busy]="busy()" [error]="dialogError()"
        message="Tài xế sẽ được thông báo và không thể gửi lại yêu cầu cho đơn này." reasonLabel="Lý do (không bắt buộc, tài xế sẽ thấy)"
        (confirmed)="confirm(p, $event)" (closed)="pending.set(null)" />
    }
  }`,
  styles: [`.head{display:flex;justify-content:space-between;align-items:center;gap:8px}.head h2{margin:0}
  .applicant{padding:12px 0;border-bottom:1px solid #eef1ea;display:flex;flex-direction:column;gap:4px}.applicant.closed{opacity:.65}
  .row{display:flex;justify-content:space-between;align-items:center;gap:8px}.meta{font-size:13px}.message{margin:4px 0;font-style:italic;font-size:14px}
  .actions-row{margin-top:6px}`],
})
export class ApplicantsPanelComponent implements OnInit {
  readonly shipmentId = input.required<string>();
  /** Choosing is only possible while the order is published and still needs drivers. */
  readonly canChoose = input(false);
  /** Vehicles the order needs and already has (several drivers are chosen one by one). */
  readonly vehicleCount = input(1);
  readonly assignedCount = input(0);
  readonly changed = output<void>();
  /** Bumped by the page whenever the order changed elsewhere (another panel acted); the list is read again. */
  readonly refresh = input(0);
  private seenRefresh: number | null = null;
  private readonly refreshed = effect(() => {
    const n = this.refresh();
    // The first value is the one the panel was created with (ngOnInit loads); later changes reload.
    if (this.seenRefresh !== null && n !== this.seenRefresh) untracked(() => this.load());
    this.seenRefresh = n;
  });
  private readonly api = inject(CustomerApplicationApi);
  private readonly toast = inject(ToastService);
  protected readonly applicants = signal<Applicant[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly pending = signal<Pending | null>(null);
  protected readonly labels = APPLICATION_STATUS_LABELS;
  protected readonly vehicleLabels = VEHICLE_TYPE_LABELS;
  protected readonly badge = applicationBadge;
  protected readonly bodyLabel = bodyTypeLabel;

  ngOnInit(): void { this.load(); }

  protected acceptMessage(): string {
    const left = this.vehicleCount() - this.assignedCount();
    if (this.vehicleCount() > 1 && left > 1)
      return `Tài xế này nhận 1 trong ${this.vehicleCount()} xe của đơn. Sau khi chọn, đơn còn cần ${left - 1} xe nữa.`;
    return this.vehicleCount() > 1
      ? 'Đây là xe cuối cùng đơn cần: đơn sẽ đủ tài xế và các yêu cầu còn lại sẽ tự động bị từ chối.'
      : 'Đơn sẽ được giao cho tài xế này và các yêu cầu khác sẽ tự động bị từ chối. Trước khi chuyến bắt đầu, bạn vẫn có thể gỡ hoặc thay tài xế.';
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list(this.shipmentId()).subscribe({
      next: list => { this.applicants.set(list); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
  }

  ask(kind: Pending['kind'], applicant: Applicant): void { this.dialogError.set(null); this.pending.set({ kind, applicant }); }

  confirm(p: Pending, reason: string | null): void {
    if (this.busy()) return;
    this.busy.set(true);
    const request = p.kind === 'accept' ? this.api.accept(this.shipmentId(), p.applicant.id) : this.api.reject(this.shipmentId(), p.applicant.id, reason);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.pending.set(null);
        this.toast.success(p.kind === 'accept' ? `Đã chọn ${p.applicant.driver?.fullName || 'tài xế'} cho đơn.` : 'Đã từ chối yêu cầu.');
        this.load();
        this.changed.emit();
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        const hint = err.code ? MARKETPLACE_ERROR_HINTS[err.code] : undefined;
        if (err.code === 'SHIPMENT_ALREADY_ASSIGNED' || err.code === 'APPLICATION_NOT_PENDING' || err.code === 'SHIPMENT_NOT_AVAILABLE') {
          // The situation changed underneath: close the dialog and show the current state.
          this.pending.set(null);
          this.toast.error(hint ?? err.message);
          this.load();
          this.changed.emit();
        } else {
          this.dialogError.set(err.message);
        }
      },
    });
  }
}
