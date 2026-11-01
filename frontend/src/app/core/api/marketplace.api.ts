import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';
import { Item, SelectedService, ShipmentStatus, VehicleRequirement } from './shipment.api';
import { VehicleType } from './account.api';

export type ApplicationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED' | 'RELEASED';
export type MarketplaceSort = 'pickupTime' | 'price' | 'distance' | 'weight' | 'publishedAt';

/** Area only: exact address and contact are revealed to the assigned driver. */
export interface Area { province: string; district: string | null; ward: string | null; }
export interface MarketplaceShipment {
  id: string; shipmentCode: string; pickupTime: string; deliveryDeadline: string | null; applicationDeadline: string | null;
  pickup: Area | null; delivery: Area | null; deliveryStopCount: number; vehicleTypeCode: string | null; vehicleTypeName: string | null;
  bodyTypeCode: string | null; totalQuantity: number; totalWeightKg: number; totalVolumeM3: number; price: number | null; distanceKm: number | null; publishedAt: string | null;
  /** Vehicles the order needs and how many are already assigned. */
  vehicleCount: number; assignedVehicleCount: number;
}
export interface MarketplaceListItem { shipment: MarketplaceShipment; myApplicationStatus: ApplicationStatus | null; }
export interface StopView {
  stopType: 'PICKUP' | 'DELIVERY'; sequenceNo: number; province: string; district: string | null; ward: string | null;
  fullAddress: string | null; contactName: string | null; contactPhone: string | null; note: string | null;
}
export interface VehicleOption {
  id: string; plateNumber: string; vehicleType: VehicleType; bodyTypeCode: string | null; maxPayloadKg: number; lengthCm: number; widthCm: number; heightCm: number;
  /** reasons include schedule conflicts of this vehicle. */
  eligible: boolean; reasons: string[];
}
export interface MyApplicationBrief {
  id: string; status: ApplicationStatus; vehicleId: string; plateNumber: string | null; driverMessage: string | null; responseReason: string | null;
  appliedAt: string; respondedAt: string | null;
}
export interface MarketplaceShipmentDetail {
  id: string; shipmentCode: string; status: ShipmentStatus; pickupTime: string; deliveryDeadline: string | null; applicationDeadline: string | null;
  vehicle: VehicleRequirement; totalQuantity: number; totalWeightKg: number; totalVolumeM3: number; customerNote: string | null;
  price: number | null; distanceKm: number | null; assignedToMe: boolean; stops: StopView[]; items: Item[]; services: SelectedService[];
  driverApproved: boolean; canApply: boolean; unavailableReason: string | null; myApplication: MyApplicationBrief | null; vehicles: VehicleOption[];
  vehicleCount: number; assignedVehicleCount: number; scheduleConflicts: string[];
}
export interface ShipmentBrief {
  id: string; shipmentCode: string; status: ShipmentStatus; pickupTime: string | null; pickup: Area | null; delivery: Area | null;
  totalWeightKg: number; price: number | null; distanceKm: number | null;
}
export interface VehicleBrief { id: string; plateNumber: string; vehicleType: VehicleType; bodyTypeCode: string | null; maxPayloadKg: number; lengthCm: number; widthCm: number; heightCm: number; }
export interface DriverApplication {
  id: string; status: ApplicationStatus; shipment: ShipmentBrief | null; vehicle: VehicleBrief | null; driverMessage: string | null; responseReason: string | null;
  appliedAt: string; respondedAt: string | null; withdrawable: boolean; stops: StopView[];
}
/** No avatar or rating exists yet; completedTrips is counted from real orders. */
export interface DriverBrief { driverId: string; fullName: string | null; licenseClass: string | null; completedTrips: number; phone: string | null; }
export interface Applicant {
  id: string; status: ApplicationStatus; driver: DriverBrief | null; vehicle: VehicleBrief | null; driverMessage: string | null; responseReason: string | null;
  appliedAt: string; respondedAt: string | null; vehicleEligible: boolean; vehicleIssues: string[];
}
export interface AdminMarketplaceShipment {
  id: string; shipmentCode: string; status: ShipmentStatus; pickupTime: string | null; pickup: Area | null; delivery: Area | null; price: number | null;
  vehicleCount: number; pendingApplications: number; totalApplications: number; assignedDriverId: string | null; assignedDriverName: string | null;
  assignedAt: string | null; publishedAt: string | null; assignedVehicleCount: number;
}
export interface AdminApplication {
  id: string; status: ApplicationStatus; shipmentId: string; shipmentCode: string | null; shipmentStatus: ShipmentStatus | null; driverId: string;
  driverName: string | null; driverPhone: string | null; vehicleId: string; plateNumber: string | null; driverMessage: string | null; responseReason: string | null;
  appliedAt: string; respondedAt: string | null;
}
/** Assignment history row; actorType SYSTEM = a scheduled job (systemActor names it), performedBy is then null. */
export interface AdminAssignment {
  id: string; shipmentId: string; shipmentCode: string | null; action: 'ASSIGNED' | 'UNASSIGNED' | 'REPLACED'; newDriverId: string | null; newDriverName: string | null;
  previousDriverId: string | null; previousDriverName: string | null; vehicleId: string | null; plateNumber: string | null; applicationId: string | null;
  reason: string | null; performedBy: string | null; performedByName: string | null; actorType: 'USER' | 'SYSTEM'; systemActor: string | null;
  assignmentId: string | null; createdAt: string;
}
export type AssignmentStatus = 'ACTIVE' | 'RELEASED' | 'FINISHED';
/** One vehicle slot of an order; lead = the driver who runs the trip. */
export interface AssignmentView {
  id: string; slotNo: number; status: AssignmentStatus; lead: boolean; driver: DriverBrief | null; vehicle: VehicleBrief | null; applicationId: string | null;
  busyFrom: string | null; busyUntil: string | null; assignedAt: string; releasedAt: string | null; releaseReason: string | null; finishedAt: string | null;
}
export interface AssignmentOverview {
  shipmentId: string; shipmentCode: string; status: ShipmentStatus; vehicleCount: number; assignedCount: number; remaining: number;
  canChange: boolean; changeBlockedReason: string | null; assignments: AssignmentView[]; history: AdminAssignment[];
}
export interface ScheduleEntry {
  assignmentId: string; shipmentId: string; shipmentCode: string; shipmentStatus: ShipmentStatus; driverId: string; driverName: string | null;
  vehicleId: string; plateNumber: string | null; busyFrom: string | null; busyUntil: string | null; lead: boolean;
}

export interface MarketplaceQuery {
  keyword?: string; pickupProvince?: string; pickupDistrict?: string; deliveryProvince?: string; deliveryDistrict?: string; vehicleTypeId?: string;
  minPrice?: number | null; maxPrice?: number | null; minWeight?: number | null; maxWeight?: number | null; maxDistance?: number | null;
  sortBy?: MarketplaceSort; sortDirection?: 'asc' | 'desc'; page: number; size: number;
}

export const APPLICATION_STATUS_LABELS: Record<ApplicationStatus, string> = {
  PENDING: 'Chờ khách chọn', ACCEPTED: 'Được chọn', REJECTED: 'Không được chọn', WITHDRAWN: 'Đã rút', EXPIRED: 'Đã đóng', RELEASED: 'Đã bị gỡ khỏi đơn',
};

export function areaText(a: Area | null): string {
  return a ? [a.ward, a.district, a.province].filter(Boolean).join(', ') : '—';
}

/** Drops empty values so only filters the user set reach the API. */
export function toParams(query: object): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    params = params.set(key, String(value));
  }
  return params;
}

const enc = encodeURIComponent;

@Injectable({ providedIn: 'root' })
export class DriverMarketplaceApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/driver`;
  list(query: MarketplaceQuery): Observable<PageResponse<MarketplaceListItem>> {
    return this.http.get<ApiResponse<PageResponse<MarketplaceListItem>>>(`${this.base}/marketplace/shipments`, { params: toParams(query) }).pipe(map(r => r.data));
  }
  detail(id: string): Observable<MarketplaceShipmentDetail> {
    return this.http.get<ApiResponse<MarketplaceShipmentDetail>>(`${this.base}/marketplace/shipments/${enc(id)}`).pipe(map(r => r.data));
  }
  apply(id: string, vehicleId: string, driverMessage: string | null): Observable<DriverApplication> {
    return this.http.post<ApiResponse<DriverApplication>>(`${this.base}/marketplace/shipments/${enc(id)}/applications`, { vehicleId, driverMessage }).pipe(map(r => r.data));
  }
  mine(status: ApplicationStatus | '', page: number): Observable<PageResponse<DriverApplication>> {
    return this.http.get<ApiResponse<PageResponse<DriverApplication>>>(`${this.base}/applications/my`, { params: toParams({ status, page, size: 20 }) }).pipe(map(r => r.data));
  }
  application(id: string): Observable<DriverApplication> { return this.http.get<ApiResponse<DriverApplication>>(`${this.base}/applications/${enc(id)}`).pipe(map(r => r.data)); }
  withdraw(id: string, reason: string | null): Observable<DriverApplication> {
    return this.http.post<ApiResponse<DriverApplication>>(`${this.base}/applications/${enc(id)}/withdraw`, { reason }).pipe(map(r => r.data));
  }
}

@Injectable({ providedIn: 'root' })
export class CustomerApplicationApi {
  private readonly http = inject(HttpClient);
  private url(shipmentId: string): string { return `${API_BASE}/customer/shipments/${enc(shipmentId)}/applications`; }
  list(shipmentId: string): Observable<Applicant[]> { return this.http.get<ApiResponse<Applicant[]>>(this.url(shipmentId)).pipe(map(r => r.data)); }
  accept(shipmentId: string, id: string): Observable<Applicant> { return this.http.post<ApiResponse<Applicant>>(`${this.url(shipmentId)}/${enc(id)}/accept`, null).pipe(map(r => r.data)); }
  reject(shipmentId: string, id: string, reason: string | null): Observable<Applicant> {
    return this.http.post<ApiResponse<Applicant>>(`${this.url(shipmentId)}/${enc(id)}/reject`, { reason }).pipe(map(r => r.data));
  }
  private assignmentsUrl(shipmentId: string): string { return `${API_BASE}/customer/shipments/${enc(shipmentId)}/assignments`; }
  assignments(shipmentId: string): Observable<AssignmentOverview> {
    return this.http.get<ApiResponse<AssignmentOverview>>(this.assignmentsUrl(shipmentId)).pipe(map(r => r.data));
  }
  release(shipmentId: string, assignmentId: string, reason: string): Observable<AssignmentOverview> {
    return this.http.post<ApiResponse<AssignmentOverview>>(`${this.assignmentsUrl(shipmentId)}/${enc(assignmentId)}/release`, { reason }).pipe(map(r => r.data));
  }
  replace(shipmentId: string, assignmentId: string, applicationId: string, reason: string): Observable<AssignmentOverview> {
    return this.http.post<ApiResponse<AssignmentOverview>>(`${this.assignmentsUrl(shipmentId)}/${enc(assignmentId)}/replace`, { applicationId, reason }).pipe(map(r => r.data));
  }
}

@Injectable({ providedIn: 'root' })
export class AdminMarketplaceApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/admin/marketplace`;
  shipments(status: ShipmentStatus | '', keyword: string, page: number, understaffed = false): Observable<PageResponse<AdminMarketplaceShipment>> {
    return this.http.get<ApiResponse<PageResponse<AdminMarketplaceShipment>>>(`${this.base}/shipments`,
      { params: toParams({ status, keyword, page, size: 20, understaffed: understaffed || null }) }).pipe(map(r => r.data));
  }
  shipmentAssignments(shipmentId: string): Observable<AssignmentOverview> {
    return this.http.get<ApiResponse<AssignmentOverview>>(`${this.base}/shipments/${enc(shipmentId)}/assignments`).pipe(map(r => r.data));
  }
  release(shipmentId: string, assignmentId: string, reason: string): Observable<AssignmentOverview> {
    return this.http.post<ApiResponse<AssignmentOverview>>(`${this.base}/shipments/${enc(shipmentId)}/assignments/${enc(assignmentId)}/release`, { reason }).pipe(map(r => r.data));
  }
  schedules(query: { driverId?: string; vehicleId?: string; from?: string; to?: string; page: number }): Observable<PageResponse<ScheduleEntry>> {
    return this.http.get<ApiResponse<PageResponse<ScheduleEntry>>>(`${this.base}/schedules`, { params: toParams({ ...query, size: 50 }) }).pipe(map(r => r.data));
  }
  applications(status: ApplicationStatus | '', page: number): Observable<PageResponse<AdminApplication>> {
    return this.http.get<ApiResponse<PageResponse<AdminApplication>>>(`${this.base}/applications`, { params: toParams({ status, page, size: 20 }) }).pipe(map(r => r.data));
  }
  assignments(page: number): Observable<PageResponse<AdminAssignment>> {
    return this.http.get<ApiResponse<PageResponse<AdminAssignment>>>(`${this.base}/assignments`, { params: toParams({ page, size: 20 }) }).pipe(map(r => r.data));
  }
}
