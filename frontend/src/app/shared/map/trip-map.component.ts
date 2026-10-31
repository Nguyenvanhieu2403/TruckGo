import { AfterViewChecked, Component, ElementRef, computed, inject, input, viewChild } from '@angular/core';
import { MAP_PROVIDER, MapPoint } from './map-provider';
import { StopProgress, Tracking } from '../../core/api/trip.api';

/** Driver position and stops. Only real coordinates are used; stops without coordinates are listed by address. */
@Component({
  selector: 'app-trip-map',
  standalone: true,
  template: `
  @if (provider.interactive) { <div #canvas class="canvas" role="img" aria-label="Bản đồ chuyến đi"></div> }
  @else { <p class="muted small">Chưa cấu hình nhà cung cấp bản đồ; hiển thị vị trí dạng danh sách. Bấm "Xem bản đồ" để mở vị trí thật trên Google Maps.</p> }
  <ul class="points">
    @if (driverPoint(); as d) {
      <li class="driver"><span class="dot dot-driver" aria-hidden="true"></span><div><strong>Tài xế</strong> · {{ coord(d.latitude) }}, {{ coord(d.longitude) }}
        @if (link(d); as url) { · <a [href]="url" target="_blank" rel="noopener noreferrer">Xem bản đồ</a> }</div></li>
    }
    @for (s of stops(); track s.stopId) {
      <li><span class="dot" [class.dot-pickup]="s.stopType === 'PICKUP'" aria-hidden="true"></span>
        <div><strong>{{ s.sequenceNo }}. {{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</strong> · {{ s.fullAddress || s.province }}
          @if (s.latitude !== null && s.longitude !== null) {
            @if (link({ latitude: s.latitude, longitude: s.longitude, label: '', kind: 'delivery' }); as url) { · <a [href]="url" target="_blank" rel="noopener noreferrer">Xem bản đồ</a> }
          }</div></li>
    }
  </ul>`,
  styles: [`.canvas{height:320px;border-radius:6px;background:#eef1ea}.points{list-style:none;padding:0;margin:8px 0 0}.points li{display:flex;gap:10px;align-items:flex-start;padding:6px 0;font-size:14px}
  .dot{flex:none;width:12px;height:12px;border-radius:50%;background:#c4452f;margin-top:4px}.dot-pickup{background:#78a648}.dot-driver{background:#2f6fc4;box-shadow:0 0 0 4px #2f6fc433}.small{font-size:12px}`],
})
export class TripMapComponent implements AfterViewChecked {
  readonly tracking = input<Tracking | null>(null);
  readonly stops = input<StopProgress[]>([]);
  protected readonly provider = inject(MAP_PROVIDER);
  private readonly canvas = viewChild<ElementRef<HTMLElement>>('canvas');
  protected readonly driverPoint = computed<MapPoint | null>(() => {
    const t = this.tracking();
    return t && t.latitude !== null && t.longitude !== null ? { latitude: t.latitude, longitude: t.longitude, label: 'Tài xế', kind: 'driver' } : null;
  });

  link(p: MapPoint): string | null { return this.provider.externalLink(p); }

  /** Coordinates always use a decimal point (a Vietnamese decimal comma would be ambiguous in "lat, lng"). */
  coord(v: number): string { return v.toFixed(5); }

  ngAfterViewChecked(): void {
    const el = this.canvas()?.nativeElement;
    if (!el || !this.provider.render) return;
    const points: MapPoint[] = this.stops().filter(s => s.latitude !== null && s.longitude !== null)
      .map(s => ({ latitude: s.latitude!, longitude: s.longitude!, label: `${s.sequenceNo}`, kind: s.stopType === 'PICKUP' ? 'pickup' : 'delivery' }));
    const d = this.driverPoint();
    this.provider.render(el, d ? [d, ...points] : points);
  }
}
