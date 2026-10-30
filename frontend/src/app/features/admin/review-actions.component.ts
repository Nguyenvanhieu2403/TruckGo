import { Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface ReviewDecision { approve: boolean; reason?: string; }

/** Approve/reject buttons with an explicit confirmation step; rejecting requires a reason (5–500 characters). */
@Component({
  selector: 'app-review-actions',
  standalone: true,
  imports: [FormsModule],
  template: `
  @if (mode() === null) {
    <div class="actions-row">
      <button class="btn btn-primary" type="button" (click)="mode.set('approve')" [disabled]="busy()">Duyệt</button>
      <button class="btn btn-ghost" type="button" (click)="mode.set('reject')" [disabled]="busy()">Từ chối</button>
    </div>
  } @else {
    <div class="confirm" role="dialog" [attr.aria-label]="mode() === 'approve' ? 'Xác nhận duyệt' : 'Xác nhận từ chối'">
      @if (mode() === 'approve') { <p style="margin:0 0 10px">Xác nhận duyệt <strong>{{ subject() }}</strong>?</p> }
      @else {
        <div class="field" style="margin-bottom:10px">
          <label [for]="'reason-' + subject()">Lý do từ chối</label>
          <textarea [id]="'reason-' + subject()" rows="2" maxlength="500" [(ngModel)]="reason"></textarea>
          @if (reasonInvalid()) { <span class="error">Lý do dài 5–500 ký tự</span> }
        </div>
      }
      <div class="actions-row">
        <button class="btn" [class.btn-primary]="mode() === 'approve'" [class.btn-danger]="mode() === 'reject'" type="button" (click)="confirm()" [disabled]="busy()">
          {{ busy() ? 'Đang xử lý…' : mode() === 'approve' ? 'Xác nhận duyệt' : 'Xác nhận từ chối' }}</button>
        <button class="btn btn-ghost" type="button" (click)="cancel()" [disabled]="busy()">Hủy</button>
      </div>
    </div>
  }`,
})
export class ReviewActionsComponent {
  readonly subject = input.required<string>();
  readonly busy = input(false);
  readonly decided = output<ReviewDecision>();
  protected readonly mode = signal<'approve' | 'reject' | null>(null);
  protected readonly reasonInvalid = signal(false);
  protected reason = '';

  confirm(): void {
    if (this.mode() === 'approve') { this.decided.emit({ approve: true }); return; }
    const reason = this.reason.trim();
    if (reason.length < 5 || reason.length > 500) { this.reasonInvalid.set(true); return; }
    this.reasonInvalid.set(false);
    this.decided.emit({ approve: false, reason });
  }

  cancel(): void { this.mode.set(null); this.reason = ''; this.reasonInvalid.set(false); }
}
