import { Component, input, output } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';

/**
 * Generic confirmation for consequential actions, with an optional free-text reason. The parent performs the
 * request and passes `busy`/`error` back so the dialog stays open on failure.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
  <div class="backdrop" (click)="close()"></div>
  <div class="dialog card" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
    <h2 id="confirm-title">{{ title() }}</h2>
    <p class="muted">{{ message() }}</p>
    @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @if (reasonLabel()) {
      <div class="field"><label for="confirm-reason">{{ reasonLabel() }}</label>
        <textarea id="confirm-reason" rows="3" maxlength="500" [formControl]="reason"></textarea></div>
    }
    <div class="actions-row">
      <button class="btn" [class.btn-danger]="danger()" [class.btn-primary]="!danger()" type="button" (click)="confirm()" [disabled]="busy()">
        {{ busy() ? 'Đang xử lý…' : confirmLabel() }}</button>
      <button class="btn btn-ghost" type="button" (click)="close()" [disabled]="busy()">Quay lại</button>
    </div>
  </div>`,
  styles: [`.backdrop{position:fixed;inset:0;background:#18251f66;z-index:20}.dialog{position:fixed;z-index:21;left:50%;top:50%;transform:translate(-50%,-50%);width:min(480px,calc(100vw - 32px));margin:0}`],
})
export class ConfirmDialogComponent {
  readonly title = input.required<string>();
  readonly message = input('');
  readonly confirmLabel = input('Xác nhận');
  readonly danger = input(false);
  /** Shows an optional reason field when set. */
  readonly reasonLabel = input<string | null>(null);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmed = output<string | null>();
  readonly closed = output<void>();
  protected readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

  confirm(): void {
    if (this.busy()) return;
    const text = this.reason.value.trim();
    this.confirmed.emit(text ? text : null);
  }

  close(): void { if (!this.busy()) this.closed.emit(); }
}
