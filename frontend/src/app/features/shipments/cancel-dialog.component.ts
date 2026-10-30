import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ShipmentApi, ShipmentDetail } from '../../core/api/shipment.api';
import { toApiError } from '../../core/api.service';

/** Confirmation with a mandatory reason (5–500 characters), as required by the backend. */
@Component({
  selector: 'app-cancel-dialog',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <div class="backdrop" (click)="close()"></div>
  <div class="dialog card" role="dialog" aria-modal="true" aria-labelledby="cancel-title">
    <h2 id="cancel-title">Hủy đơn {{ shipmentCode() }}?</h2>
    <p class="muted">Đơn đã hủy không thể khôi phục.</p>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    <div class="field" [class.invalid]="reason.invalid && reason.touched">
      <label for="cancel-reason">Lý do hủy</label><textarea id="cancel-reason" rows="3" maxlength="500" [formControl]="reason"></textarea>
      @if (reason.invalid && reason.touched) { <span class="error">Lý do dài 5–500 ký tự</span> }
    </div>
    <div class="actions-row">
      <button class="btn btn-danger" type="button" (click)="confirm()" [disabled]="busy()">{{ busy() ? 'Đang hủy…' : 'Xác nhận hủy' }}</button>
      <button class="btn btn-ghost" type="button" (click)="close()" [disabled]="busy()">Không hủy</button>
    </div>
  </div>`,
  styles: [`.backdrop{position:fixed;inset:0;background:#18251f66;z-index:20}.dialog{position:fixed;z-index:21;left:50%;top:50%;transform:translate(-50%,-50%);width:min(480px,calc(100vw - 32px));margin:0}`],
})
export class CancelDialogComponent {
  readonly shipmentId = input.required<string>();
  readonly shipmentCode = input('');
  readonly closed = output<void>();
  readonly cancelled = output<ShipmentDetail>();
  private readonly api = inject(ShipmentApi);
  protected readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(5), Validators.maxLength(500)] });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  confirm(): void {
    if (this.busy()) return;
    this.reason.markAsTouched();
    if (this.reason.value.trim().length < 5) { this.reason.setErrors({ minlength: true }); return; }
    this.busy.set(true);
    this.api.cancel(this.shipmentId(), this.reason.value.trim()).subscribe({
      next: detail => { this.busy.set(false); this.cancelled.emit(detail); },
      error: (e: unknown) => { this.error.set(toApiError(e).message); this.busy.set(false); },
    });
  }

  close(): void { if (!this.busy()) this.closed.emit(); }
}
