import { Component, input } from '@angular/core';
import { StopView, areaText } from '../../core/api/marketplace.api';

/** Route for a driver: area only, full address and contact once the order is theirs. */
@Component({
  selector: 'app-stop-list',
  standalone: true,
  template: `
  @for (stop of stops(); track stop.sequenceNo) {
    <div class="stop"><span class="badge" [class.badge--approved]="stop.stopType === 'PICKUP'">{{ stop.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</span>
      <div>@if (stop.fullAddress) {
          <strong>{{ stop.contactName }}</strong> · <a [href]="'tel:' + stop.contactPhone">{{ stop.contactPhone }}</a><br>{{ stop.fullAddress }}
          @if (stop.note) { <br><em class="muted">{{ stop.note }}</em> }
        } @else { {{ area(stop) }} }</div></div>
  }
  @if (!revealed()) { <p class="muted hint">Địa chỉ chi tiết và số điện thoại liên hệ sẽ hiển thị khi khách hàng chọn bạn.</p> }`,
  styles: [`.stop{display:flex;gap:12px;padding:10px 0;border-bottom:1px solid #eef1ea;font-size:14px}.stop .badge{min-width:82px;text-align:center;align-self:flex-start}.hint{font-size:13px;margin:10px 0 0}`],
})
export class StopListComponent {
  readonly stops = input.required<StopView[]>();
  readonly revealed = input(false);
  protected area(s: StopView): string { return areaText({ province: s.province, district: s.district, ward: s.ward }); }
}
