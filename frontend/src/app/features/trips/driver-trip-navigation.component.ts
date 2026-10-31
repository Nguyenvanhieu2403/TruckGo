import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api.service';
import { DriverTrip, DriverTripApi, STOP_STATUS_LABELS } from '../../core/api/trip.api';
import { TripMapComponent } from '../../shared/map/trip-map.component';
import { GpsPanelComponent } from './gps-panel.component';
import { directionsLink, telLink } from './trip-labels';

/** Driving view: the current stop large, with turn-by-turn handed to the device's map app. No route optimisation. */
@Component({
  selector: 'app-driver-trip-navigation',
  standalone: true,
  imports: [RouterLink, GpsPanelComponent, TripMapComponent],
  template: `
  <main class="container">
    @if (trip(); as t) {
      <a [routerLink]="['/driver/trips', t.id]" class="kicker">← Chi tiết chuyến {{ t.shipmentCode }}</a>
      @if (current(); as s) {
        <section class="card current" aria-labelledby="cur-title">
          <div class="kicker">Điểm {{ s.sequenceNo }}/{{ t.stops.length }} · {{ stopLabels[s.status] }}</div>
          <h1 id="cur-title">{{ s.stopType === 'PICKUP' ? 'Lấy hàng' : 'Giao hàng' }}</h1>
          <p class="addr">{{ s.fullAddress }}</p>
          <p>{{ s.contactName }}@if (tel(s.contactPhone); as href) { · <a [href]="href">{{ s.contactPhone }}</a> }</p>
          @if (s.stopNote) { <p class="muted">{{ s.stopNote }}</p> }
          <div class="actions-row">
            @if (directions(s.fullAddress, s.latitude, s.longitude); as url) { <a class="btn btn-primary" [href]="url" target="_blank" rel="noopener noreferrer">Mở chỉ đường</a> }
            <a class="btn btn-ghost" [routerLink]="['/driver/trips', t.id]">Cập nhật trạng thái</a>
          </div>
        </section>
      } @else { <div class="alert alert-success" style="margin-top:14px">Đã hoàn thành tất cả điểm dừng.</div> }
      @if (t.status === 'IN_TRANSIT' || t.trackingSession) {
        <app-gps-panel [shipmentId]="t.id" [allowed]="t.trackingAllowed" [session]="t.trackingSession" [policy]="t.trackingPolicy" (changed)="load()" />
      }
      <section class="card"><h2>Các điểm dừng</h2><app-trip-map [stops]="t.stops" /></section>
    } @else if (error()) { <div class="alert alert-error" role="alert">{{ error() }}</div> }
    @else { <div class="card" aria-busy="true">@for (n of [1, 2]; track n) { <div class="skeleton-row"></div> }</div> }
  </main>`,
  styles: [`.current{border-color:#2f6fc4;margin-top:12px}.current h1{margin:4px 0}.addr{font-size:18px;font-weight:600}`],
})
export class DriverTripNavigationComponent implements OnInit {
  private readonly api = inject(DriverTripApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly trip = signal<DriverTrip | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly current = computed(() => this.trip()?.stops.find(s => s.current) ?? null);
  protected readonly stopLabels = STOP_STATUS_LABELS;
  protected readonly tel = telLink;
  protected readonly directions = directionsLink;

  ngOnInit(): void { this.load(); }

  load(): void {
    this.api.get(this.route.snapshot.paramMap.get('shipmentId') ?? '').subscribe({ next: t => this.trip.set(t), error: (e: unknown) => this.error.set(toApiError(e).message) });
  }
}
