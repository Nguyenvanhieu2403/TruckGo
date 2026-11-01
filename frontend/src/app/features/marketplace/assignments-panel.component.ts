import { Component, OnInit, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { toApiError } from '../../core/api.service';
import { AdminMarketplaceApi, AdminAssignment, Applicant, AssignmentOverview, AssignmentView, CustomerApplicationApi } from '../../core/api/marketplace.api';
import { ToastService } from '../../shared/toast.service';
import { MARKETPLACE_ERROR_HINTS } from './marketplace-labels';

type Change = { kind: 'release' | 'replace'; assignment: AssignmentView };

const ACTIONS: Record<AdminAssignment['action'], string> = { ASSIGNED: 'Phân công', UNASSIGNED: 'Gỡ tài xế', REPLACED: 'Thay tài xế' };

/**
 * Who carries an order: vehicles needed / assigned / missing, each driver and vehicle, and the assignment history.
 * Before the trip starts the customer (or an admin) can remove a driver or replace them with a pending applicant;
 * both need a reason, which the driver sees.
 */
@Component({
  selector: 'app-assignments-panel',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule],
  template: `
  <section class="card" aria-labelledby="assignments-title" data-testid="assignments-panel">
    <div class="head"><h2 id="assignments-title">Phân công tài xế</h2>
      <button class="btn btn-ghost" type="button" (click)="load()" [disabled]="loading()">Tải lại</button></div>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (loading() && !data()) { <div class="skeleton-row"></div> }
    @if (data(); as d) {
      <div class="progress" data-testid="assignment-progress">
        <strong>{{ d.assignedCount }}/{{ d.vehicleCount }} xe đã có tài xế</strong>
        @if (d.remaining > 0) { <span class="badge badge--pending_review">Còn thiếu {{ d.remaining }} xe</span> } @else { <span class="badge badge--approved">Đủ xe</span> }
        <div class="bar" role="progressbar" [attr.aria-valuenow]="d.assignedCount" aria-valuemin="0" [attr.aria-valuemax]="d.vehicleCount">
          <span [style.width.%]="d.vehicleCount ? (100 * d.assignedCount) / d.vehicleCount : 0"></span></div>
      </div>
      @if (!d.canChange && d.changeBlockedReason && active().length) { <p class="muted small">{{ d.changeBlockedReason }}</p> }
      @for (a of active(); track a.id) {
        <article class="slot" data-testid="assignment">
          <div class="row"><strong>Xe {{ a.slotNo }} · {{ a.driver?.fullName || 'Tài xế' }}</strong>@if (a.lead) { <span class="badge">Chạy chuyến</span> }</div>
          <div class="meta">{{ a.vehicle?.plateNumber }}@if (a.driver?.phone; as phone) { · <a [href]="'tel:' + phone">{{ phone }}</a> }</div>
          @if (a.busyFrom) { <div class="meta muted">Bận từ {{ a.busyFrom | date: 'HH:mm dd/MM' }} đến {{ a.busyUntil | date: 'HH:mm dd/MM' }}</div> }
          @if (d.canChange) {
            <div class="actions-row">
              <button class="btn btn-ghost" type="button" (click)="ask('release', a)">Gỡ tài xế</button>
              @if (!admin() && candidates().length) { <button class="btn btn-ghost" type="button" (click)="ask('replace', a)">Thay tài xế</button> }
            </div>
          }
        </article>
      } @empty { <p class="empty">Chưa có tài xế nào được phân công.</p> }
      @if (d.history.length) {
        <details class="history"><summary>Lịch sử phân công ({{ d.history.length }})</summary>
          <ol>
            @for (h of d.history; track h.id) {
              <li data-testid="assignment-history"><strong>{{ actions[h.action] }}</strong>:
                @switch (h.action) {
                  @case ('ASSIGNED') { {{ h.newDriverName || 'tài xế' }} ({{ h.plateNumber }}) }
                  @case ('UNASSIGNED') { {{ h.previousDriverName || 'tài xế' }} ({{ h.plateNumber }}) }
                  @case ('REPLACED') { {{ h.previousDriverName || 'tài xế' }} → {{ h.newDriverName || 'tài xế' }} }
                }
                @if (h.reason) { — “{{ h.reason }}” }
                <div class="muted small">{{ h.createdAt | date: 'HH:mm dd/MM/yyyy' }} ·
                  {{ h.actorType === 'SYSTEM' ? 'Hệ thống (' + h.systemActor + ')' : (h.performedByName || 'Người dùng') }}</div></li>
            }
          </ol>
        </details>
      }
    }
  </section>
  @if (change(); as c) {
    <div class="backdrop" (click)="close()"></div>
    <div class="dialog card" role="dialog" aria-modal="true" aria-labelledby="change-title">
      <h2 id="change-title">{{ c.kind === 'release' ? 'Gỡ ' : 'Thay ' }}{{ c.assignment.driver?.fullName || 'tài xế' }}?</h2>
      <p class="small">{{ c.kind === 'release'
        ? 'Tài xế sẽ được thông báo. Nếu đơn đã đủ xe, đơn sẽ quay lại Marketplace để tìm tài xế khác.'
        : 'Tài xế hiện tại được gỡ và tài xế bạn chọn được phân công ngay.' }}</p>
      @if (c.kind === 'replace') {
        <div class="field"><label for="replacement">Tài xế thay thế</label>
          <select id="replacement" [formControl]="replacement">
            <option value="" disabled>Chọn tài xế đang chờ</option>
            @for (a of candidates(); track a.id) { <option [value]="a.id">{{ a.driver?.fullName || 'Tài xế' }} · {{ a.vehicle?.plateNumber }}</option> }
          </select></div>
      }
      <div class="field" [class.invalid]="reason.touched && reason.invalid"><label for="change-reason">Lý do (bắt buộc, tài xế sẽ thấy)</label>
        <textarea id="change-reason" rows="3" [formControl]="reason" maxlength="500"></textarea>
        @if (reason.touched && reason.invalid) { <span class="error">Vui lòng nhập lý do</span> }</div>
      @if (dialogError()) { <div class="alert alert-error" role="alert">{{ dialogError() }}</div> }
      <div class="actions-row">
        <button class="btn btn-danger" type="button" (click)="confirm(c)" [disabled]="busy()">{{ busy() ? 'Đang xử lý…' : c.kind === 'release' ? 'Gỡ tài xế' : 'Thay tài xế' }}</button>
        <button class="btn btn-ghost" type="button" (click)="close()" [disabled]="busy()">Quay lại</button>
      </div>
    </div>
  }`,
  styles: [`.head{display:flex;justify-content:space-between;align-items:center;gap:8px}.head h2{margin:0}
  .progress{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:8px 0}.bar{flex-basis:100%;height:6px;border-radius:3px;background:#eef1ea;overflow:hidden}
  .bar span{display:block;height:100%;background:var(--tg-accent, #2f7d4f)}
  .slot{padding:10px 0;border-bottom:1px solid #eef1ea;display:flex;flex-direction:column;gap:4px}.row{display:flex;justify-content:space-between;gap:8px}.meta{font-size:13px}
  .history ol{padding-left:18px;font-size:13px}.history li{margin:6px 0}
  .backdrop{position:fixed;inset:0;background:#18251f66;z-index:20}.dialog{position:fixed;z-index:21;left:50%;top:50%;transform:translate(-50%,-50%);width:min(480px,calc(100vw - 32px));margin:0}`],
})
export class AssignmentsPanelComponent implements OnInit {
  readonly shipmentId = input.required<string>();
  /** Admin endpoints instead of the customer's own. */
  readonly admin = input(false);
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
  private readonly customerApi = inject(CustomerApplicationApi);
  private readonly adminApi = inject(AdminMarketplaceApi);
  private readonly toast = inject(ToastService);
  protected readonly data = signal<AssignmentOverview | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly change = signal<Change | null>(null);
  protected readonly actions = ACTIONS;
  protected readonly active = computed(() => (this.data()?.assignments ?? []).filter(a => a.status === 'ACTIVE'));
  /** Pending applicants whose vehicle fits (customer view) that can replace an assigned driver. */
  private readonly applicants = signal<Applicant[]>([]);
  protected readonly candidates = computed(() => this.applicants().filter(a => a.status === 'PENDING' && a.vehicleEligible));
  protected readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(500)] });
  protected readonly replacement = new FormControl('', { nonNullable: true, validators: [Validators.required] });

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const request = this.admin() ? this.adminApi.shipmentAssignments(this.shipmentId()) : this.customerApi.assignments(this.shipmentId());
    request.subscribe({
      next: d => { this.data.set(d); this.loading.set(false); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.loading.set(false); },
    });
    if (!this.admin()) this.customerApi.list(this.shipmentId()).subscribe({ next: list => this.applicants.set(list), error: () => this.applicants.set([]) });
  }

  ask(kind: Change['kind'], assignment: AssignmentView): void {
    this.reason.reset('');
    this.replacement.reset('');
    this.dialogError.set(null);
    this.change.set({ kind, assignment });
  }

  close(): void { if (!this.busy()) this.change.set(null); }

  confirm(c: Change): void {
    this.reason.markAsTouched();
    if (this.reason.invalid || (c.kind === 'replace' && this.replacement.invalid) || this.busy()) return;
    const reason = this.reason.value.trim();
    let request: Observable<AssignmentOverview>;
    if (c.kind === 'release') request = this.admin() ? this.adminApi.release(this.shipmentId(), c.assignment.id, reason) : this.customerApi.release(this.shipmentId(), c.assignment.id, reason);
    else request = this.customerApi.replace(this.shipmentId(), c.assignment.id, this.replacement.value, reason);
    this.busy.set(true);
    request.subscribe({
      next: d => {
        this.busy.set(false);
        this.change.set(null);
        this.data.set(d);
        this.load();
        this.toast.success(c.kind === 'release' ? 'Đã gỡ tài xế khỏi đơn.' : 'Đã thay tài xế.');
        this.changed.emit();
      },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.dialogError.set((err.code && MARKETPLACE_ERROR_HINTS[err.code]) || err.message);
        if (err.code === 'TRIP_ALREADY_STARTED' || err.code === 'ASSIGNMENT_NOT_ACTIVE') this.load();
      },
    });
  }
}
