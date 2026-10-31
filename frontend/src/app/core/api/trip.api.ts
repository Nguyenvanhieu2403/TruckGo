import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';
import { toParams } from './marketplace.api';
import { Item, SelectedService, ShipmentStatus, StatusChange, VehicleRequirement } from './shipment.api';
import { Quote } from './pricing.api';
import { VehicleType } from './account.api';

export type StopStatus = 'PENDING' | 'ARRIVED' | 'COMPLETED' | 'SKIPPED';
export type NextAction = 'ARRIVE_PICKUP' | 'CONFIRM_PICKUP' | 'START_TRIP' | 'ARRIVE_STOP' | 'COMPLETE_STOP' | 'DELIVER' | 'NONE';
export type TrackingState = 'NOT_STARTED' | 'LIVE' | 'STALE' | 'OFFLINE' | 'PAUSED' | 'ENDED';
export type TripView = 'ACTIVE' | 'UPCOMING' | 'COMPLETED' | 'INCIDENT' | 'ALL';
export type PhotoPurpose = 'PICKUP_PHOTO' | 'DELIVERY_PHOTO' | 'INCIDENT_PHOTO';
export type IncidentType = 'VEHICLE_BREAKDOWN' | 'TRAFFIC_JAM' | 'ACCIDENT' | 'GOODS_DAMAGED' | 'CUSTOMER_UNAVAILABLE' | 'WRONG_ADDRESS' | 'WEATHER' | 'OTHER';
export type IncidentStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED';

export interface StopProgress {
  stopId: string; stopType: 'PICKUP' | 'DELIVERY'; sequenceNo: number; province: string; district: string | null; ward: string | null;
  fullAddress: string | null; contactName: string | null; contactPhone: string | null; stopNote: string | null; latitude: number | null; longitude: number | null;
  status: StopStatus; arrivedAt: string | null; departedAt: string | null; progressNote: string | null; current: boolean;
}
export interface Photo { id: string; fileType: PhotoPurpose | 'GOODS_PHOTO'; fileName: string; fileSize: number; createdAt: string; contentUrl: string; }
export interface TripVehicle { id: string; plateNumber: string; vehicleType: VehicleType; maxPayloadKg: number | null; }
export interface Milestones { assignedAt: string | null; driverArrivedAt: string | null; pickedUpAt: string | null; inTransitAt: string | null; deliveredAt: string | null; completedAt: string | null; }
export interface TrackingPolicy { movingIntervalSeconds: number; idleIntervalSeconds: number; minDistanceMeters: number; maxAgeSeconds: number; }
/** Coordinates come only from real fixes; state says how old they are. */
export interface Tracking {
  state: TrackingState; session: 'ACTIVE' | 'PAUSED' | 'ENDED' | null; latitude: number | null; longitude: number | null; accuracy: number | null;
  speed: number | null; heading: number | null; recordedAt: string | null; ageSeconds: number | null;
}
export interface TrackingPoint { latitude: number; longitude: number; accuracy: number | null; speed: number | null; heading: number | null; recordedAt: string; }
export interface LocationAck { accepted: boolean; reason: string | null; recordedAt: string; policy: TrackingPolicy; }
export interface Confirmation { pending: boolean; expiresAt: string; attemptsLeft: number; resendAvailableAt: string | null; channel: string; locked: boolean; }
export interface Incident {
  id: string; shipmentId: string; shipmentCode: string | null; type: IncidentType; description: string; status: IncidentStatus; latitude: number | null; longitude: number | null;
  occurredAt: string; reportedBy: string; reportedByName: string | null; resolutionNote: string | null; resolvedAt: string | null; createdAt: string; photos: Photo[];
}
export interface TripSummary {
  id: string; shipmentCode: string; status: ShipmentStatus; pickupTime: string | null; deliveryDeadline: string | null; pickupAddress: string | null;
  deliveryAddress: string | null; stopCount: number; totalQuantity: number; totalWeightKg: number; price: number | null; vehicle: TripVehicle | null; openIncidents: number;
}
export interface DriverTrip {
  id: string; shipmentCode: string; status: ShipmentStatus; pickupTime: string | null; deliveryDeadline: string | null; customerName: string | null; customerPhone: string | null;
  requirement: VehicleRequirement; vehicle: TripVehicle | null; totalQuantity: number; totalWeightKg: number; totalVolumeM3: number; customerNote: string | null;
  stops: StopProgress[]; items: Item[]; services: SelectedService[]; quote: Quote | null; milestones: Milestones; statusHistory: StatusChange[]; photos: Photo[];
  nextAction: NextAction; nextStopId: string | null; trackingAllowed: boolean; trackingSession: 'ACTIVE' | 'PAUSED' | 'ENDED' | null; trackingPolicy: TrackingPolicy;
  deliveryPhotoRequired: boolean; awaitingCustomerConfirmation: boolean; openIncidents: number;
}
export interface CustomerTrip {
  id: string; shipmentCode: string; status: ShipmentStatus; driver: { driverId: string; fullName: string | null; phone: string | null; licenseClass: string | null } | null;
  vehicle: TripVehicle | null; stops: StopProgress[]; milestones: Milestones; statusHistory: StatusChange[]; photos: Photo[]; tracking: Tracking;
  estimatedArrival: string | null; confirmation: Confirmation | null; openIncidents: number;
}
export interface MilestonePayload { latitude?: number | null; longitude?: number | null; note?: string | null; attachments?: string[]; }
/** ageMs: how old the fix is by the device clock; the server dates the fix from it, so a wrong device clock does not matter. */
export interface LocationPayload { latitude: number; longitude: number; accuracy: number | null; speed: number | null; heading: number | null; recordedAt: string; ageMs: number; }
export interface IncidentPayload { type: IncidentType; description: string; latitude?: number | null; longitude?: number | null; attachments?: string[]; }

export const INCIDENT_TYPE_LABELS: Record<IncidentType, string> = {
  VEHICLE_BREAKDOWN: 'Hỏng xe', TRAFFIC_JAM: 'Kẹt xe', ACCIDENT: 'Tai nạn', GOODS_DAMAGED: 'Hàng hư hỏng', CUSTOMER_UNAVAILABLE: 'Không liên lạc được người nhận',
  WRONG_ADDRESS: 'Sai địa chỉ', WEATHER: 'Thời tiết xấu', OTHER: 'Khác',
};
export const INCIDENT_STATUS_LABELS: Record<IncidentStatus, string> = { OPEN: 'Mới', IN_PROGRESS: 'Đang xử lý', RESOLVED: 'Đã xử lý', CANCELLED: 'Đã hủy' };
export const TRACKING_STATE_LABELS: Record<TrackingState, string> = {
  NOT_STARTED: 'Chưa bắt đầu', LIVE: 'Đang cập nhật', STALE: 'Vị trí cũ', OFFLINE: 'Mất tín hiệu', PAUSED: 'Tài xế tạm dừng chia sẻ', ENDED: 'Đã kết thúc',
};
export const STOP_STATUS_LABELS: Record<StopStatus, string> = { PENDING: 'Chưa đến', ARRIVED: 'Đã đến', COMPLETED: 'Hoàn thành', SKIPPED: 'Bỏ qua' };

const enc = encodeURIComponent;

@Injectable({ providedIn: 'root' })
export class DriverTripApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/driver/trips`;
  list(q: { view: TripView; status?: ShipmentStatus | ''; fromDate?: string; toDate?: string; keyword?: string; page: number }): Observable<PageResponse<TripSummary>> {
    return this.http.get<ApiResponse<PageResponse<TripSummary>>>(this.base, { params: toParams({ ...q, size: 20 }) }).pipe(map(r => r.data));
  }
  get(id: string): Observable<DriverTrip> { return this.http.get<ApiResponse<DriverTrip>>(`${this.base}/${enc(id)}`).pipe(map(r => r.data)); }
  /** arrived | pickup | start | deliver */
  act(id: string, action: 'arrived' | 'pickup' | 'start' | 'deliver', body: MilestonePayload): Observable<DriverTrip> {
    return this.http.post<ApiResponse<DriverTrip>>(`${this.base}/${enc(id)}/${action}`, body).pipe(map(r => r.data));
  }
  stop(id: string, stopId: string, action: 'arrive' | 'complete', body: MilestonePayload): Observable<DriverTrip> {
    return this.http.post<ApiResponse<DriverTrip>>(`${this.base}/${enc(id)}/stops/${enc(stopId)}/${action}`, body).pipe(map(r => r.data));
  }
  location(id: string, body: LocationPayload): Observable<LocationAck> {
    return this.http.post<ApiResponse<LocationAck>>(`${this.base}/${enc(id)}/location`, body).pipe(map(r => r.data));
  }
  sharing(id: string, pause: boolean): Observable<Tracking> {
    return this.http.post<ApiResponse<Tracking>>(`${this.base}/${enc(id)}/tracking/${pause ? 'pause' : 'resume'}`, null).pipe(map(r => r.data));
  }
  upload(id: string, purpose: PhotoPurpose, file: File): Observable<Photo> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<ApiResponse<Photo>>(`${this.base}/${enc(id)}/photos`, form, { params: { purpose } }).pipe(map(r => r.data));
  }
  incidents(id: string): Observable<Incident[]> { return this.http.get<ApiResponse<Incident[]>>(`${this.base}/${enc(id)}/incidents`).pipe(map(r => r.data)); }
  report(id: string, body: IncidentPayload): Observable<Incident> {
    return this.http.post<ApiResponse<Incident>>(`${this.base}/${enc(id)}/incidents`, body).pipe(map(r => r.data));
  }
}

@Injectable({ providedIn: 'root' })
export class CustomerTripApi {
  private readonly http = inject(HttpClient);
  private url(id: string): string { return `${API_BASE}/customer/shipments/${enc(id)}`; }
  trip(id: string): Observable<CustomerTrip> { return this.http.get<ApiResponse<CustomerTrip>>(`${this.url(id)}/trip`).pipe(map(r => r.data)); }
  tracking(id: string): Observable<Tracking> { return this.http.get<ApiResponse<Tracking>>(`${this.url(id)}/tracking`).pipe(map(r => r.data)); }
  history(id: string, page = 0): Observable<PageResponse<TrackingPoint>> {
    return this.http.get<ApiResponse<PageResponse<TrackingPoint>>>(`${this.url(id)}/tracking/history`, { params: toParams({ page, size: 500 }) }).pipe(map(r => r.data));
  }
  confirm(id: string, otp: string, note: string | null): Observable<CustomerTrip> {
    return this.http.post<ApiResponse<CustomerTrip>>(`${this.url(id)}/confirm-delivery`, { otp, note }).pipe(map(r => r.data));
  }
  resend(id: string): Observable<Confirmation> { return this.http.post<ApiResponse<Confirmation>>(`${this.url(id)}/delivery-otp/resend`, null).pipe(map(r => r.data)); }
  incidents(id: string): Observable<Incident[]> { return this.http.get<ApiResponse<Incident[]>>(`${this.url(id)}/incidents`).pipe(map(r => r.data)); }
  streamUrl(id: string): string { return `${this.url(id)}/tracking/stream`; }
}

@Injectable({ providedIn: 'root' })
export class AdminTripApi {
  private readonly http = inject(HttpClient);
  incidents(q: { type?: IncidentType | ''; status?: IncidentStatus | ''; from?: string; to?: string; page: number }): Observable<PageResponse<Incident>> {
    return this.http.get<ApiResponse<PageResponse<Incident>>>(`${API_BASE}/admin/incidents`, { params: toParams({ ...q, size: 20 }) }).pipe(map(r => r.data));
  }
  incident(id: string): Observable<Incident> { return this.http.get<ApiResponse<Incident>>(`${API_BASE}/admin/incidents/${enc(id)}`).pipe(map(r => r.data)); }
  update(id: string, status: IncidentStatus, note: string | null): Observable<Incident> {
    return this.http.patch<ApiResponse<Incident>>(`${API_BASE}/admin/incidents/${enc(id)}`, { status, note }).pipe(map(r => r.data));
  }
  trip(id: string): Observable<CustomerTrip> { return this.http.get<ApiResponse<CustomerTrip>>(`${API_BASE}/admin/trips/${enc(id)}`).pipe(map(r => r.data)); }
}
