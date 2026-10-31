import { InjectionToken } from '@angular/core';

export interface MapPoint { latitude: number; longitude: number; label: string; kind: 'driver' | 'pickup' | 'delivery'; }

/**
 * Where trip positions are drawn. The default renders no tiles (no API key is configured or committed): it lists the
 * real points and links each one to an external map. A Google Maps or Mapbox provider can be supplied with
 * `{ provide: MAP_PROVIDER, useValue: ... }` once a browser key is available; nothing else in the app changes.
 */
export interface MapProvider {
  readonly name: string;
  /** True when the provider draws an interactive map into the given element. */
  readonly interactive: boolean;
  render?(element: HTMLElement, points: MapPoint[]): void;
  /** A link to view one real point elsewhere, or null. */
  externalLink(point: MapPoint): string | null;
}

export class LinkOnlyMapProvider implements MapProvider {
  readonly name = 'none';
  readonly interactive = false;
  externalLink(p: MapPoint): string { return `https://www.google.com/maps/search/?api=1&query=${p.latitude},${p.longitude}`; }
}

export const MAP_PROVIDER = new InjectionToken<MapProvider>('MAP_PROVIDER', { providedIn: 'root', factory: () => new LinkOnlyMapProvider() });
