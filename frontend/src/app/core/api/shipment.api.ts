import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';
import { ServiceGroup, ServiceValueType } from './catalog.api';

export type ShipmentStatus = 'DRAFT' | 'PUBLISHED' | 'ASSIGNED' | 'DRIVER_ARRIVED' | 'PICKED_UP' | 'IN_TRANSIT' | 'DELIVERED' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';
export type StopType = 'PICKUP' | 'DELIVERY';

export interface StopPayload {
  stopType: StopType; sequenceNo: number; contactName: string; contactPhone: string; province: string; district: string | null; ward: string | null;
  addressDetail: string; latitude: number | null; longitude: number | null; note: string | null;
}
export interface ItemPayload {
  itemName: string; category: string; description: string | null; quantity: number; weightPerUnitKg: number;
  lengthCm: number | null; widthCm: number | null; heightCm: number | null;
}
export interface ServicePayload { serviceCode: string; serviceValue: string; }
/** No totals, status, owner or price: the backend computes or decides those. */
export interface DraftPayload {
  pickupTime: string | null; deliveryDeadline: string | null; vehicleTypeCode: string | null; bodyTypeCode: string | null; vehicleCount: number;
  minimumPayloadKg: number | null; minCargoLengthCm: number | null; minCargoWidthCm: number | null; minCargoHeightCm: number | null;
  customerNote: string | null; stops: StopPayload[]; items: ItemPayload[]; services: ServicePayload[]; version?: number;
}

export interface Stop extends StopPayload { id: string; fullAddress: string; }
export interface Item extends ItemPayload { id: string; categoryName: string; totalWeightKg: number; totalVolumeM3: number | null; }
export interface SelectedService { serviceCode: string; serviceName: string; group: ServiceGroup | null; valueType: ServiceValueType | null; serviceValue: string; valueLabel: string | null; }
export interface Attachment { id: string; fileName: string; contentType: string; fileSize: number; fileType: string; createdAt: string; contentUrl: string; }
export interface StatusChange { oldStatus: ShipmentStatus | null; newStatus: ShipmentStatus; changedBy: string; changeReason: string | null; createdAt: string; }
export interface ShipmentWarning { code: string; message: string; }
export interface ShipmentActions { editable: boolean; publishable: boolean; cancellable: boolean; deletable: boolean; attachmentsEditable: boolean; }
export interface VehicleRequirement {
  vehicleTypeCode: string | null; vehicleTypeName: string | null; vehicleTypeMaxPayloadKg: number | null; bodyTypeCode: string | null; bodyTypeName: string | null;
  vehicleCount: number; minimumPayloadKg: number | null; minCargoLengthCm: number | null; minCargoWidthCm: number | null; minCargoHeightCm: number | null;
}
export interface ShipmentDetail {
  id: string; shipmentCode: string; status: ShipmentStatus; customerId: string; pickupTime: string | null; deliveryDeadline: string | null;
  vehicle: VehicleRequirement; totalQuantity: number; totalWeightKg: number; totalVolumeM3: number; customerNote: string | null;
  priceStatus: 'NOT_QUOTED' | 'AWAITING_REVIEW' | 'QUOTED' | 'ACCEPTED' | 'FINAL'; estimatedPrice: number | null; finalPrice: number | null;
  pickup: Stop | null; delivery: Stop | null; stops: Stop[]; items: Item[]; services: SelectedService[]; attachments: Attachment[];
  statusHistory: StatusChange[]; warnings: ShipmentWarning[]; actions: ShipmentActions;
  publishedAt: string | null; cancelledAt: string | null; cancellationReason: string | null; createdAt: string; updatedAt: string; version: number;
}
export interface ShipmentSummary {
  id: string; shipmentCode: string; status: ShipmentStatus; createdAt: string; pickupTime: string | null; pickupAddress: string | null; deliveryAddress: string | null;
  deliveryStopCount: number; totalQuantity: number; totalWeightKg: number; totalVolumeM3: number; vehicleTypeCode: string | null; vehicleTypeName: string | null;
  actions: ShipmentActions;
}
export interface ShipmentQuery {
  page: number; size: number; status?: ShipmentStatus | ''; keyword?: string; fromDate?: string; toDate?: string; sortBy?: string; sortDirection?: 'asc' | 'desc';
}

const BASE = `${API_BASE}/shipments`;

@Injectable({ providedIn: 'root' })
export class ShipmentApi {
  private readonly http = inject(HttpClient);

  mine(query: ShipmentQuery): Observable<PageResponse<ShipmentSummary>> {
    return this.http.get<ApiResponse<PageResponse<ShipmentSummary>>>(`${BASE}/my`, { params: params(query) }).pipe(map(r => r.data));
  }
  all(query: ShipmentQuery): Observable<PageResponse<ShipmentSummary>> {
    return this.http.get<ApiResponse<PageResponse<ShipmentSummary>>>(`${API_BASE}/admin/shipments`, { params: params(query) }).pipe(map(r => r.data));
  }
  get(id: string): Observable<ShipmentDetail> { return this.http.get<ApiResponse<ShipmentDetail>>(`${BASE}/${enc(id)}`).pipe(map(r => r.data)); }
  createDraft(body: DraftPayload): Observable<ShipmentDetail> { return this.http.post<ApiResponse<ShipmentDetail>>(`${BASE}/draft`, body).pipe(map(r => r.data)); }
  updateDraft(id: string, body: DraftPayload): Observable<ShipmentDetail> { return this.http.put<ApiResponse<ShipmentDetail>>(`${BASE}/${enc(id)}/draft`, body).pipe(map(r => r.data)); }
  publish(id: string): Observable<ShipmentDetail> { return this.http.post<ApiResponse<ShipmentDetail>>(`${BASE}/${enc(id)}/publish`, null).pipe(map(r => r.data)); }
  cancel(id: string, reason: string): Observable<ShipmentDetail> { return this.http.post<ApiResponse<ShipmentDetail>>(`${BASE}/${enc(id)}/cancel`, { reason }).pipe(map(r => r.data)); }
  delete(id: string): Observable<void> { return this.http.delete<ApiResponse<null>>(`${BASE}/${enc(id)}`).pipe(map(() => undefined)); }

  attachments(id: string): Observable<Attachment[]> { return this.http.get<ApiResponse<Attachment[]>>(`${BASE}/${enc(id)}/attachments`).pipe(map(r => r.data)); }
  upload(id: string, file: File): Observable<Attachment> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<ApiResponse<Attachment>>(`${BASE}/${enc(id)}/attachments`, form).pipe(map(r => r.data));
  }
  deleteAttachment(id: string, attachmentId: string): Observable<void> {
    return this.http.delete<ApiResponse<null>>(`${BASE}/${enc(id)}/attachments/${enc(attachmentId)}`).pipe(map(() => undefined));
  }
  /** Photos are private: fetched with the access token (interceptor) and shown from an object URL. */
  attachmentBlob(contentUrl: string): Observable<Blob> { return this.http.get(contentUrl, { responseType: 'blob' }); }
}

function enc(value: string): string { return encodeURIComponent(value); }

function params(query: ShipmentQuery): HttpParams {
  let p = new HttpParams().set('page', query.page).set('size', query.size);
  for (const key of ['status', 'keyword', 'fromDate', 'toDate', 'sortBy', 'sortDirection'] as const) {
    const value = query[key];
    if (value) p = p.set(key, String(value).trim());
  }
  return p;
}
