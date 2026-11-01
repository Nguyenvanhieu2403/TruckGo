import { InjectionToken } from '@angular/core';
import { MapTiles } from '../../core/api/geo.api';

export interface MapPoint { latitude: number; longitude: number; label: string; kind: 'driver' | 'pickup' | 'delivery'; }

/**
 * Draws real points on a map. The default uses Leaflet with the tiles the server announces (free OpenStreetMap
 * tiles, no key); a Google Maps or Mapbox implementation can be provided with
 * `{ provide: MAP_PROVIDER, useValue: ... }` without touching the pages.
 */
export interface MapProvider {
  readonly name: string;
  /** Draws (or redraws) the points into the element; returns a disposer. */
  render(element: HTMLElement, tiles: MapTiles, points: MapPoint[], refit: boolean): Promise<void>;
  destroy(element: HTMLElement): void;
  /** A link to view one real point elsewhere. */
  externalLink(point: MapPoint): string | null;
}

const COLORS: Record<MapPoint['kind'], string> = { driver: '#2f6fc4', pickup: '#5f8f34', delivery: '#c4452f' };

/** Leaflet loaded on demand (only pages with a map pay for it); circle markers, so no marker images are needed. */
export class LeafletMapProvider implements MapProvider {
  readonly name = 'leaflet';
  private readonly maps = new WeakMap<HTMLElement, { map: import('leaflet').Map; layer: import('leaflet').LayerGroup }>();

  async render(element: HTMLElement, tiles: MapTiles, points: MapPoint[], refit: boolean): Promise<void> {
    // Leaflet 1.9 ships CommonJS: a dynamic import exposes it under `default` in the production bundle.
    const mod = await import('leaflet');
    const L = ((mod as unknown as { default?: typeof mod }).default ?? mod) as typeof mod;
    let entry = this.maps.get(element);
    if (!entry) {
      const map = L.map(element, { zoomControl: true, attributionControl: true, scrollWheelZoom: false });
      L.tileLayer(tiles.url, { attribution: escapeHtml(tiles.attribution), maxZoom: tiles.maxZoom, subdomains: 'abcd' }).addTo(map);
      entry = { map, layer: L.layerGroup().addTo(map) };
      this.maps.set(element, entry);
      refit = true;
    }
    entry.layer.clearLayers();
    for (const p of points) {
      L.circleMarker([p.latitude, p.longitude], {
        radius: p.kind === 'driver' ? 9 : 7, color: '#ffffff', weight: 2, fillColor: COLORS[p.kind], fillOpacity: 0.95,
      }).bindTooltip(p.label).addTo(entry.layer);
    }
    if (points.length === 0) { entry.map.setView([16.0, 106.0], 5); return; }   // Viet Nam overview
    const driver = points.find(p => p.kind === 'driver');
    if (refit) {
      if (points.length === 1) entry.map.setView([points[0].latitude, points[0].longitude], 15);
      else entry.map.fitBounds(L.latLngBounds(points.map(p => [p.latitude, p.longitude] as [number, number])), { padding: [30, 30], maxZoom: 16 });
    } else if (driver && !entry.map.getBounds().contains([driver.latitude, driver.longitude])) {
      entry.map.panTo([driver.latitude, driver.longitude]);   // keep the moving truck in view without fighting the user's zoom
    }
  }

  destroy(element: HTMLElement): void {
    this.maps.get(element)?.map.remove();
    this.maps.delete(element);
  }

  externalLink(p: MapPoint): string {
    return `https://www.openstreetmap.org/?mlat=${p.latitude}&mlon=${p.longitude}#map=17/${p.latitude}/${p.longitude}`;
  }
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

export const MAP_PROVIDER = new InjectionToken<MapProvider>('MAP_PROVIDER', { providedIn: 'root', factory: () => new LeafletMapProvider() });
