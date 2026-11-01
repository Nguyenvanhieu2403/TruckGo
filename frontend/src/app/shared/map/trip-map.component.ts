import { Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { MAP_PROVIDER, MapPoint } from './map-provider';
import { GeoApi, MapTiles } from '../../core/api/geo.api';
import { StopProgress, Tracking } from '../../core/api/trip.api';

/**
 * Driver position and stops on a free map (when the server announces tiles), with a text list underneath that also
 * serves screen readers and the no-map case. Only real coordinates are drawn; stops without them are listed only.
 */
@Component({
  selector: 'app-trip-map',
  standalone: true,
  template: `
  @if (tiles()) { <div #canvas class="canvas" role="img" [attr.aria-label]="ariaLabel()"></div> }
  @else if (checked()) { <p class="muted small">Chưa cấu hình bản đồ; hiển thị vị trí dạng danh sách.</p> }
  <ul class="points">
    @if (driverPoint(); as d) {
      <li><span class="dot dot-driver" aria-hidden="true"></span><div><strong>Tài xế</strong> · {{ coord(d.latitude) }}, {{ coord(d.longitude) }}
        @if (link(d); as url) { · <a [href]="url" target="_blank" rel="noopener noreferrer">Xem bản đồ</a> }</div></li>
    }
    @for (s of stops(); track s.stopId) {
      <li><span class="dot" [class.dot-pickup]="s.stopType === 'PICKUP'" aria-hidden="true"></span>
        <div><strong>{{ s.sequenceNo }}. {{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</strong> · {{ s.fullAddress || s.province }}
          @if (s.latitude === null) { <span class="muted small"> (chưa có tọa độ)</span> }</div></li>
    }
  </ul>`,
  styles: [`.canvas{height:340px;border-radius:6px;background:#eef1ea;z-index:0}.points{list-style:none;padding:0;margin:8px 0 0}
  .points li{display:flex;gap:10px;align-items:flex-start;padding:6px 0;font-size:14px}
  .dot{flex:none;width:12px;height:12px;border-radius:50%;background:#c4452f;margin-top:4px}.dot-pickup{background:#5f8f34}.dot-driver{background:#2f6fc4;box-shadow:0 0 0 4px #2f6fc433}
  .small{font-size:12px}@media(max-width:560px){.canvas{height:260px}}`],
})
export class TripMapComponent implements OnDestroy {
  readonly tracking = input<Tracking | null>(null);
  readonly stops = input<StopProgress[]>([]);
  private readonly provider = inject(MAP_PROVIDER);
  private readonly canvas = viewChild<ElementRef<HTMLElement>>('canvas');
  protected readonly tiles = signal<MapTiles | null>(null);
  protected readonly checked = signal(false);
  private rendered = false;
  protected readonly driverPoint = computed<MapPoint | null>(() => {
    const t = this.tracking();
    return t && t.latitude !== null && t.longitude !== null ? { latitude: t.latitude, longitude: t.longitude, label: 'Tài xế', kind: 'driver' } : null;
  });
  private readonly points = computed<MapPoint[]>(() => {
    const stops = this.stops().filter(s => s.latitude !== null && s.longitude !== null).map<MapPoint>(s => ({
      latitude: s.latitude!, longitude: s.longitude!, label: `${s.sequenceNo}. ${s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng'}`,
      kind: s.stopType === 'PICKUP' ? 'pickup' : 'delivery',
    }));
    const d = this.driverPoint();
    return d ? [d, ...stops] : stops;
  });
  protected readonly ariaLabel = computed(() => `Bản đồ: ${this.driverPoint() ? 'vị trí tài xế và ' : ''}${this.points().filter(p => p.kind !== 'driver').length} điểm dừng có tọa độ`);

  constructor() {
    inject(GeoApi).status().subscribe(s => { this.tiles.set(s.tiles); this.checked.set(true); });
    effect(() => {
      const points = this.points();
      const tiles = this.tiles();
      const el = this.canvas()?.nativeElement;
      if (!el || !tiles) return;
      void this.provider.render(el, tiles, points, !this.rendered).catch(e => console.error('Bản đồ không hiển thị được', e));
      this.rendered = true;
    });
  }


  ngOnDestroy(): void { const el = this.canvas()?.nativeElement; if (el) this.provider.destroy(el); }

  link(p: MapPoint): string | null { return this.provider.externalLink(p); }

  /** Coordinates always use a decimal point (a Vietnamese decimal comma would be ambiguous in "lat, lng"). */
  coord(v: number): string { return v.toFixed(5); }
}
