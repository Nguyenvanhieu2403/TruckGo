import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GeoApi, GeoCandidate, PRECISION_LABELS } from '../../../core/api/geo.api';
import { toApiError } from '../../../core/api.service';
import { StopForm } from '../shipment-form';

/**
 * Optional address lookup for one stop. Choosing a suggestion fills the coordinates (asking first when the customer
 * already entered different ones); the typed address fields are never replaced. Hidden when no provider is configured.
 */
@Component({
  selector: 'app-address-search',
  standalone: true,
  imports: [FormsModule],
  template: `
  @if (enabled()) {
    <div class="lookup">
      <div class="row">
        <label class="sr-only" [for]="'geo-q' + index()">Tìm địa chỉ trên bản đồ</label>
        <input [id]="'geo-q' + index()" [(ngModel)]="query" (keydown.enter)="$event.preventDefault(); search()" placeholder="Tìm địa chỉ trên bản đồ để lấy tọa độ" />
        <button class="btn btn-ghost" type="button" (click)="useTyped()" [disabled]="busy()">Dùng địa chỉ đã nhập</button>
        <button class="btn btn-ghost" type="button" (click)="search()" [disabled]="busy() || query.trim().length < 3">{{ busy() ? 'Đang tìm…' : 'Tìm' }}</button>
      </div>
      @if (error()) { <div class="alert alert-warn" role="alert">{{ error() }}</div> }
      @if (results(); as list) {
        @if (list.length === 0) { <p class="muted small" role="status">Không tìm thấy địa chỉ phù hợp. Bạn có thể nhập tọa độ thủ công hoặc để trống.</p> }
        <ul class="results" role="listbox" [attr.aria-label]="'Gợi ý địa chỉ cho điểm ' + (index() + 1)">
          @for (c of list; track c.placeId ?? c.formattedAddress) {
            <li><button type="button" role="option" class="result" (click)="choose(c)" data-testid="geo-result">
              <span>{{ c.formattedAddress }}</span>
              <span class="muted small">{{ precision[c.precision] }}@if (c.partialMatch) { · khớp một phần } · {{ c.latitude.toFixed(6) }}, {{ c.longitude.toFixed(6) }}</span>
            </button></li>
          }
        </ul>
      }
      @if (pending(); as c) {
        <div class="alert alert-info" role="dialog" aria-label="Thay tọa độ">
          Điểm này đã có tọa độ {{ coords() }}. Thay bằng tọa độ của "{{ c.formattedAddress }}"?
          <div class="actions-row"><button class="btn btn-primary" type="button" (click)="apply(c)">Thay tọa độ</button>
            <button class="btn btn-ghost" type="button" (click)="pending.set(null)">Giữ tọa độ cũ</button></div>
        </div>
      }
      @if (chosen(); as c) { <p class="muted small" data-testid="geo-chosen">Đã gắn tọa độ từ "{{ c.formattedAddress }}". Kiểm tra lại địa chỉ chi tiết bên dưới.</p> }
    </div>
  } @else if (checked()) {
    <p class="muted small" data-testid="geo-disabled">Chưa cấu hình dịch vụ bản đồ: nhập địa chỉ thủ công, tọa độ có thể để trống.</p>
  }`,
  styles: [`.lookup{margin-bottom:12px}.row{display:flex;gap:8px;flex-wrap:wrap}.row input{flex:1;min-width:200px}.small{font-size:12px}
  .results{list-style:none;padding:0;margin:8px 0 0;border:1px solid var(--tg-line);border-radius:6px}.result{display:flex;flex-direction:column;gap:2px;width:100%;
  text-align:left;padding:8px 10px;border:0;border-bottom:1px solid #eef1ea;background:#fff;cursor:pointer;font:inherit}.result:hover,.result:focus-visible{background:var(--tg-accent-soft)}
  .sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}`],
})
export class AddressSearchComponent implements OnInit {
  readonly stop = input.required<StopForm>();
  readonly index = input(0);
  private readonly api = inject(GeoApi);
  protected readonly enabled = signal(false);
  protected readonly checked = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly results = signal<GeoCandidate[] | null>(null);
  protected readonly pending = signal<GeoCandidate | null>(null);
  protected readonly chosen = signal<GeoCandidate | null>(null);
  protected readonly precision = PRECISION_LABELS;
  protected query = '';

  ngOnInit(): void {
    this.api.status().subscribe(s => { this.enabled.set(s.enabled); this.checked.set(true); });
  }

  coords(): string {
    const v = this.stop().getRawValue();
    return `${v.latitude}, ${v.longitude}`;
  }

  useTyped(): void {
    const v = this.stop().getRawValue();
    this.query = [v.addressDetail, v.ward, v.district, v.province].map(x => (x ?? '').trim()).filter(Boolean).join(', ');
    this.search();
  }

  search(): void {
    const q = this.query.trim();
    if (q.length < 3 || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.search(q).subscribe({
      next: list => { this.busy.set(false); this.results.set(list); },
      error: (e: unknown) => {
        const err = toApiError(e);
        this.busy.set(false);
        this.results.set(null);
        this.error.set(err.status === 429 ? 'Bạn tìm quá nhanh, vui lòng thử lại sau ít giây.' : err.message);
      },
    });
  }

  choose(c: GeoCandidate): void {
    const v = this.stop().getRawValue();
    const hasOther = v.latitude !== null && v.longitude !== null && (v.latitude !== c.latitude || v.longitude !== c.longitude);
    if (hasOther) { this.pending.set(c); return; }
    this.apply(c);
  }

  apply(c: GeoCandidate): void {
    this.stop().patchValue({ latitude: c.latitude, longitude: c.longitude });
    this.stop().markAsDirty();
    this.pending.set(null);
    this.results.set(null);
    this.chosen.set(c);
  }
}
