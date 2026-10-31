import { InjectionToken } from '@angular/core';

/** The browser Geolocation API, injectable so tests can drive it. Null when the browser has none. */
export const GEOLOCATION = new InjectionToken<Geolocation | null>('GEOLOCATION', {
  providedIn: 'root',
  factory: () => (typeof navigator !== 'undefined' && 'geolocation' in navigator ? navigator.geolocation : null),
});

/** Asks once for the current position; resolves null when unavailable, denied or timed out (milestones work without it). */
export function currentPosition(geo: Geolocation | null, timeoutMs = 8000): Promise<GeolocationPosition | null> {
  if (!geo) return Promise.resolve(null);
  return new Promise(resolve => geo.getCurrentPosition(p => resolve(p), () => resolve(null), { enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 30_000 }));
}

/** Great-circle distance in metres. */
export function metersBetween(a: { latitude: number; longitude: number }, b: { latitude: number; longitude: number }): number {
  const r = 6_371_000, rad = Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * rad, dLon = (b.longitude - a.longitude) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.latitude * rad) * Math.cos(b.latitude * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
}
