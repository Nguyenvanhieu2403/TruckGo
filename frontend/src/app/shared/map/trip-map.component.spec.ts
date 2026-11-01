import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TripMapComponent } from './trip-map.component';
import { MAP_PROVIDER, MapProvider } from './map-provider';
import { ok, stop, tracking } from '../../features/trips/trip-test-data';

describe('TripMapComponent', () => {
  let http: HttpTestingController;
  const provider: MapProvider = { name: 'fake', render: vi.fn(() => Promise.resolve()), destroy: vi.fn(), externalLink: () => 'https://www.openstreetmap.org/x' };
  beforeEach(() => {
    vi.mocked(provider.render).mockClear();
    TestBed.configureTestingModule({ imports: [TripMapComponent], providers: [provideHttpClient(), provideHttpClientTesting(), { provide: MAP_PROVIDER, useValue: provider }] });
    http = TestBed.inject(HttpTestingController);
  });

  function render(tiles: boolean) {
    const fixture = TestBed.createComponent(TripMapComponent);
    const withCoords = { ...stop(2, 'DELIVERY'), latitude: 20.8449, longitude: 106.6881 };
    fixture.componentRef.setInput('stops', [stop(1, 'PICKUP'), withCoords]);
    fixture.componentRef.setInput('tracking', tracking());
    fixture.detectChanges();
    http.expectOne('/api/v1/geo/status').flush(ok({ provider: 'photon', enabled: true,
      tiles: tiles ? { url: 'https://{s}.tiles.test/{z}/{x}/{y}.png', attribution: '© OpenStreetMap contributors', maxZoom: 19 } : null }));
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('draws only real coordinates on the free map and lists stops without them', () => {
    const { el } = render(true);
    expect(el.querySelector('.canvas')).not.toBeNull();
    const [, , points] = vi.mocked(provider.render).mock.calls.at(-1)!;
    expect(points.map(p => p.kind)).toEqual(['driver', 'delivery']);   // the pickup has no coordinates: not drawn
    expect(el.textContent).toContain('(chưa có tọa độ)');
    expect(el.textContent).toContain('21.02850, 105.85420');
  });

  it('falls back to the list when no tiles are configured', () => {
    const { el } = render(false);
    expect(el.querySelector('.canvas')).toBeNull();
    expect(el.textContent).toContain('Chưa cấu hình bản đồ');
    expect(provider.render).not.toHaveBeenCalled();
  });
});
