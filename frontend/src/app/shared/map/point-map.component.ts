import { Component, ElementRef, OnDestroy, effect, inject, input, signal, viewChild } from '@angular/core';
import { GeoApi, MapTiles } from '../../core/api/geo.api';
import { MAP_PROVIDER } from './map-provider';

/** Small preview of one coordinate (e.g. a stop being entered) so the customer can check it is the right place. */
@Component({
  selector: 'app-point-map',
  standalone: true,
  template: `@if (tiles() && latitude() !== null && longitude() !== null) { <div #canvas class="mini" role="img" [attr.aria-label]="'Vị trí ' + label() + ' trên bản đồ'"></div> }`,
  styles: [`.mini{height:180px;border-radius:6px;margin:8px 0;background:#eef1ea;z-index:0}`],
})
export class PointMapComponent implements OnDestroy {
  readonly latitude = input<number | null>(null);
  readonly longitude = input<number | null>(null);
  readonly label = input('');
  readonly kind = input<'pickup' | 'delivery'>('delivery');
  private readonly provider = inject(MAP_PROVIDER);
  private readonly canvas = viewChild<ElementRef<HTMLElement>>('canvas');
  protected readonly tiles = signal<MapTiles | null>(null);

  constructor() {
    inject(GeoApi).status().subscribe(s => this.tiles.set(s.tiles));
    effect(() => {
      const el = this.canvas()?.nativeElement, tiles = this.tiles(), lat = this.latitude(), lng = this.longitude();
      if (!el || !tiles || lat === null || lng === null) return;
      void this.provider.render(el, tiles, [{ latitude: lat, longitude: lng, label: this.label(), kind: this.kind() }], true).catch(e => console.error('Bản đồ không hiển thị được', e));
    });
  }

  ngOnDestroy(): void { const el = this.canvas()?.nativeElement; if (el) this.provider.destroy(el); }
}
