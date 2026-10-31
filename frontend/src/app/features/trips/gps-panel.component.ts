import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { DriverTripApi, TrackingPolicy } from '../../core/api/trip.api';
import { toApiError } from '../../core/api.service';
import { LocationSharingService } from '../../core/geo/location-sharing.service';

/**
 * Location sharing controls for a trip in transit. Permission is requested only when the driver presses "Bắt đầu
 * chia sẻ", never before the trip is in transit. Sharing keeps running while the driver moves between pages of the
 * app and stops when they stop it, sign out, or the server reports the trip no longer accepts positions.
 */
@Component({
  selector: 'app-gps-panel',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
  <section class="card gps" aria-labelledby="gps-title">
    <h2 id="gps-title">Chia sẻ vị trí</h2>
    @if (!allowed()) {
      <p class="muted">Vị trí chỉ được chia sẻ khi chuyến đang vận chuyển. TruckGo không thu thập vị trí của bạn lúc khác.</p>
    } @else {
      <p class="muted small">Khi chia sẻ, vị trí của bạn được gửi định kỳ cho khách hàng của đơn này (và quản trị viên) cho đến khi giao hàng xong.
        Trình duyệt chỉ gửi được khi trang đang mở trên màn hình; hãy giữ trang này mở trong lúc chạy xe.</p>
      <p data-testid="gps-state"><span [class]="badgeClass()">{{ stateLabel() }}</span>
        @if (sharing.lastSentAt(); as at) { <span class="muted small"> · gửi lần cuối {{ at | date: 'HH:mm:ss' }}@if (sharing.lastAccuracy() !== null) { (±{{ sharing.lastAccuracy() | number: '1.0-0' }} m) }</span> }</p>
      @if (sharing.message()) { <div class="alert" [class.alert-error]="sharing.state() === 'denied'" [class.alert-warn]="sharing.state() !== 'denied'" role="status">{{ sharing.message() }}</div> }
      @if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
      <div class="actions-row">
        @if (active()) { <button class="btn btn-ghost" type="button" (click)="stop()" [disabled]="busy()">Dừng chia sẻ</button> }
        @else { <button class="btn btn-primary" type="button" (click)="start()" [disabled]="busy()">Bắt đầu chia sẻ vị trí</button> }
      </div>
    }
  </section>`,
  styles: [`.small{font-size:12px}.gps{border-color:#9ec0e6}`],
})
export class GpsPanelComponent {
  readonly shipmentId = input.required<string>();
  readonly allowed = input(false);
  readonly session = input<'ACTIVE' | 'PAUSED' | 'ENDED' | null>(null);
  readonly policy = input.required<TrackingPolicy>();
  readonly changed = output<void>();
  protected readonly sharing = inject(LocationSharingService);
  private readonly api = inject(DriverTripApi);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly active = computed(() => this.sharing.shipmentId() === this.shipmentId() && (this.sharing.state() === 'sharing' || this.sharing.state() === 'requesting'));
  protected readonly stateLabel = computed(() => {
    if (this.active()) return this.sharing.state() === 'requesting' ? 'Đang xin quyền vị trí…' : 'Đang chia sẻ vị trí';
    if (this.sharing.state() === 'denied') return 'Không có quyền vị trí';
    if (this.sharing.state() === 'unavailable') return 'Thiết bị không hỗ trợ định vị';
    return this.session() === 'PAUSED' ? 'Đã tạm dừng chia sẻ' : 'Chưa chia sẻ';
  });
  protected readonly badgeClass = computed(() => this.active() ? 'badge badge--approved' : this.sharing.state() === 'denied' ? 'badge badge--rejected' : 'badge');

  start(): void {
    this.error.set(null);
    const begin = () => { this.sharing.start(this.shipmentId(), this.policy()); this.busy.set(false); };
    this.busy.set(true);
    if (this.session() === 'PAUSED') {
      this.api.sharing(this.shipmentId(), false).subscribe({ next: () => { begin(); this.changed.emit(); }, error: (e: unknown) => { this.busy.set(false); this.error.set(toApiError(e).message); } });
    } else {
      begin();
    }
  }

  stop(): void {
    this.sharing.stop();
    this.busy.set(true);
    this.api.sharing(this.shipmentId(), true).subscribe({
      next: () => { this.busy.set(false); this.changed.emit(); },
      error: (e: unknown) => { this.busy.set(false); this.error.set(toApiError(e).message); },
    });
  }
}
