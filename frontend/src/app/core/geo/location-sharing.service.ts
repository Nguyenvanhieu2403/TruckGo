import { Injectable, inject, signal } from '@angular/core';
import { DriverTripApi, TrackingPolicy } from '../api/trip.api';
import { toApiError } from '../api.service';
import { GEOLOCATION, metersBetween } from './geolocation';

export type SharingState = 'idle' | 'requesting' | 'sharing' | 'denied' | 'unavailable' | 'error';

/** Below this speed (m/s, ~5 km/h) the vehicle is treated as standing still and fixes are sent less often. */
const MOVING_MS = 1.4;

/**
 * Shares the driver's position for one trip while the page is open. It asks for permission only when the driver
 * starts sharing, sends a fix when the vehicle moved far enough or the interval for its state (moving/idle) passed,
 * honours the server's rate limit and stops when told the trip no longer accepts positions.
 *
 * Browsers suspend pages in the background, so positions are only sent while the page is visible; this is shown to
 * the driver rather than claiming background tracking.
 */
@Injectable({ providedIn: 'root' })
export class LocationSharingService {
  private readonly geo = inject(GEOLOCATION);
  private readonly api = inject(DriverTripApi);
  readonly state = signal<SharingState>('idle');
  readonly message = signal<string | null>(null);
  readonly lastSentAt = signal<Date | null>(null);
  readonly lastAccuracy = signal<number | null>(null);
  readonly shipmentId = signal<string | null>(null);
  private watchId: number | null = null;
  private policy: TrackingPolicy = { movingIntervalSeconds: 8, idleIntervalSeconds: 30, minDistanceMeters: 25, maxAgeSeconds: 600 };
  private last: { latitude: number; longitude: number; at: number } | null = null;
  private pausedUntil = 0;
  private inFlight = false;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  /** GPS-grade fixes first; devices that cannot give one (no GPS, a desktop) fall back to network positioning. */
  private highAccuracy = true;

  start(shipmentId: string, policy: TrackingPolicy): void {
    if (!this.geo) { this.state.set('unavailable'); this.message.set('Trình duyệt không hỗ trợ định vị.'); return; }
    // Starting again while the device has no position (state 'error') is the driver's "try again": restart the watch.
    if (this.watchId !== null && this.shipmentId() === shipmentId && this.state() !== 'error') return;
    this.stop();
    this.shipmentId.set(shipmentId);
    this.policy = policy;
    this.highAccuracy = true;
    this.state.set('requesting');
    this.message.set(null);
    this.watch();
    // watchPosition only fires when the device reports a change; a vehicle standing still would otherwise look
    // offline to the customer. Ask for a fresh fix on the idle interval as well. This also keeps retrying while the
    // device has no position yet.
    const geo = this.geo;
    this.heartbeat = setInterval(() => geo.getCurrentPosition(p => this.onPosition(p), e => { if (e.code === e.PERMISSION_DENIED) this.onError(e); },
      { ...this.options(), maximumAge: this.highAccuracy ? 0 : 60_000 }), Math.max(this.policy.movingIntervalSeconds, 5) * 1000);
  }

  private watch(): void {
    if (!this.geo) return;
    if (this.watchId !== null) this.geo.clearWatch(this.watchId);
    this.watchId = this.geo.watchPosition(p => this.onPosition(p), e => this.onError(e), this.options());
  }

  private options(): PositionOptions {
    return this.highAccuracy ? { enableHighAccuracy: true, maximumAge: 5000, timeout: 20_000 } : { enableHighAccuracy: false, maximumAge: 60_000, timeout: 30_000 };
  }

  stop(): void {
    if (this.watchId !== null && this.geo) this.geo.clearWatch(this.watchId);
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
    this.watchId = null;
    this.last = null;
    this.inFlight = false;
    if (this.state() === 'sharing' || this.state() === 'requesting') this.state.set('idle');
    this.shipmentId.set(null);
  }

  isSharing(shipmentId: string): boolean { return this.watchId !== null && this.shipmentId() === shipmentId; }

  private onPosition(p: GeolocationPosition): void {
    if (this.state() === 'requesting' || this.state() === 'error') this.state.set('sharing');
    const id = this.shipmentId();
    if (!id || this.inFlight || Date.now() < this.pausedUntil) return;
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    // Some browsers (Safari on macOS in particular) hand back the last fix the OS had, with its original time. An old
    // fix is not the current position: do not send it, tell the driver, and wait for a fresh one.
    const ageMs = Math.max(Date.now() - p.timestamp, 0);
    if (ageMs > this.policy.maxAgeSeconds * 1000) {
      this.message.set(staleMessage(ageMs));
      return;
    }
    const point = { latitude: p.coords.latitude, longitude: p.coords.longitude };
    const moving = (p.coords.speed ?? 0) >= MOVING_MS;
    const interval = (moving ? this.policy.movingIntervalSeconds : this.policy.idleIntervalSeconds) * 1000;
    if (this.last) {
      const elapsed = p.timestamp - this.last.at;
      const moved = metersBetween(this.last, point);
      if (elapsed < this.policy.movingIntervalSeconds * 1000 || (moved < this.policy.minDistanceMeters && elapsed < interval)) return;
    }
    this.inFlight = true;
    this.api.location(id, {
      latitude: round(point.latitude), longitude: round(point.longitude), accuracy: p.coords.accuracy ?? null,
      speed: p.coords.speed === null ? null : Math.round(p.coords.speed * 3.6 * 100) / 100,   // m/s → km/h
      heading: p.coords.heading === null || Number.isNaN(p.coords.heading) ? null : p.coords.heading % 360,
      recordedAt: new Date(p.timestamp).toISOString(), ageMs: Math.round(ageMs),
    }).subscribe({
      next: ack => {
        this.inFlight = false;
        this.policy = ack.policy;
        this.last = { ...point, at: p.timestamp };
        if (ack.accepted) { this.lastSentAt.set(new Date()); this.lastAccuracy.set(p.coords.accuracy ?? null); this.message.set(null); }
      },
      error: (e: unknown) => {
        this.inFlight = false;
        const err = toApiError(e);
        if (err.status === 429) { this.pausedUntil = Date.now() + 5000; return; }
        if (err.code === 'TRACKING_NOT_ACTIVE' || err.status === 404) { this.message.set(err.message); this.stop(); return; }
        // Keep the fix as the reference point so the same old reading is not sent again on every callback.
        if (err.code === 'LOCATION_TOO_OLD' || err.code === 'LOCATION_INVALID_TIME') this.last = { ...point, at: p.timestamp };
        this.message.set(err.message);
      },
    });
  }

  private onError(e: GeolocationPositionError): void {
    if (e.code === e.PERMISSION_DENIED) {
      this.state.set('denied');
      this.message.set('Bạn đã từ chối quyền vị trí. Hãy cho phép định vị cho trang này trong cài đặt trình duyệt để chia sẻ vị trí.');
      this.stop();
      this.state.set('denied');
    } else if (e.code === e.POSITION_UNAVAILABLE && this.highAccuracy) {
      // The device has no GPS-grade fix (common on laptops and with GPS off): try Wi-Fi/network positioning instead.
      this.highAccuracy = false;
      this.state.set('error');
      this.message.set('Chưa lấy được vị trí GPS, đang thử định vị qua Wi-Fi/mạng…');
      this.watch();
    } else {
      this.state.set('error');
      this.message.set(e.code === e.TIMEOUT ? 'Không lấy được vị trí (hết thời gian chờ). Đang thử lại…' : unavailableMessage(e.message));
    }
  }
}

function round(v: number): number { return Math.round(v * 1e6) / 1e6; }

function unavailableMessage(detail: string | undefined): string {
  return 'Thiết bị chưa xác định được vị trí. Hãy bật định vị cho thiết bị và trình duyệt '
    + '(điện thoại: bật Vị trí/GPS; macOS: Cài đặt hệ thống › Quyền riêng tư & Bảo mật › Dịch vụ định vị, bật cho trình duyệt đang dùng) '
    + 'và bật Wi-Fi. TruckGo vẫn tự thử lại.' + (detail ? ` (Chi tiết: ${detail})` : '');
}

function staleMessage(ageMs: number): string {
  const minutes = Math.round(ageMs / 60_000);
  return `Thiết bị chưa có vị trí mới (vị trí gần nhất từ khoảng ${minutes} phút trước) nên chưa gửi cho khách hàng. `
    + 'Hãy bật Wi-Fi và định vị chính xác, hoặc dùng điện thoại có GPS; vị trí sẽ được gửi khi thiết bị cập nhật.';
}
