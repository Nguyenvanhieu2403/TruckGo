import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';

export type QuoteStatus = 'PENDING' | 'CALCULATED' | 'AWAITING_MANUAL_REVIEW' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED';
export type PriceComponent = 'BASE' | 'DISTANCE' | 'WEIGHT' | 'VOLUME' | 'SERVICE' | 'STOP' | 'TIME' | 'OTHER';
export type FeeType = 'FIXED' | 'PERCENTAGE' | 'PER_UNIT' | 'PER_STOP' | 'FREE';

/** appliesTo ORDER = whole order; STOP = service at the stops listed in stopSequences. */
export interface QuoteLine { lineNo: number; component: PriceComponent; code: string; name: string; description: string | null; quantity: number; unitPrice: number; amount: number;
  appliesTo?: 'ORDER' | 'STOP'; stopSequences?: number[] | null; }
export interface QuoteBreakdown {
  basePrice: number; distancePrice: number; weightSurcharge: number; volumeSurcharge: number; serviceSurcharge: number;
  additionalStopSurcharge: number; timeSurcharge: number; otherSurcharge: number;
}
/** Every amount comes from the API; the UI never computes a price. */
export interface Quote {
  id: string; quoteCode: string; quoteVersion: number; status: QuoteStatus; shipmentId: string; shipmentCode: string | null;
  vehicleTypeCode: string | null; vehicleTypeName: string | null; vehicleCount: number; distanceKm: number | null; distanceSource: 'PROVIDER' | 'MANUAL' | null;
  routeDurationMinutes: number | null; totalWeightKg: number | null; totalVolumeM3: number | null; stopCount: number;
  breakdown: QuoteBreakdown | null; totalPrice: number | null; currency: 'VND'; lines: QuoteLine[];
  reviewReasonCode: string | null; reviewReason: string | null; rejectionReason: string | null; adjustmentReason: string | null;
  expiresAt: string | null; acceptedAt: string | null; rejectedAt: string | null; supersededAt: string | null; createdAt: string;
  actions: { acceptable: boolean; rejectable: boolean; recalculable: boolean };
}
export interface AdminQuote { quote: Quote; customerId: string | null; pickupAddress: string | null; deliveryAddress: string | null; adjustedBy: string | null; pricingConfigId: string | null; pricingSnapshot: string | null; }

export interface TimeRule { name: string; daysOfWeek: string | null; startTime: string; endTime: string; feeType: 'FIXED' | 'PERCENTAGE'; amount: number; percentage: number; }
export interface ExtraFee { code: string; name: string; feeType: 'FIXED' | 'PERCENTAGE'; amount: number; percentage: number; }
export interface ServiceFee {
  id: string; pricingConfigId: string; serviceCode: string; serviceName: string; serviceOptionValue: string | null; serviceOptionLabel: string | null;
  pricingType: FeeType; amount: number; percentage: number; active: boolean; version: number; updatedAt: string;
}
export interface PricingConfig {
  id: string; name: string; vehicleTypeCode: string; vehicleTypeName: string | null; basePrice: number; pricePerKm: number; includedDistanceKm: number;
  includedWeightKg: number; pricePerExtraKg: number; includedVolumeM3: number; pricePerExtraM3: number; additionalStopPrice: number; minimumPrice: number;
  currency: 'VND'; effectiveFrom: string; effectiveTo: string | null; active: boolean; effectiveNow: boolean; timeRules: TimeRule[]; extraFees: ExtraFee[];
  serviceFees: ServiceFee[]; createdAt: string; updatedAt: string; version: number;
}
export type PricingConfigPayload = Omit<PricingConfig, 'id' | 'vehicleTypeName' | 'currency' | 'active' | 'effectiveNow' | 'serviceFees' | 'createdAt' | 'updatedAt' | 'version'> & { version?: number };
export interface ServiceFeePayload { pricingConfigId: string; serviceCode: string; serviceOptionValue: string | null; pricingType: FeeType; amount: number; percentage: number; version?: number; }
export interface ConfigHistory { id: string; action: string; oldValue: string | null; newValue: string | null; changedBy: string; changedAt: string; }

const shipmentPricing = (id: string) => `${API_BASE}/shipments/${encodeURIComponent(id)}/pricing`;

@Injectable({ providedIn: 'root' })
export class QuoteApi {
  private readonly http = inject(HttpClient);
  current(shipmentId: string): Observable<Quote | null> { return this.http.get<ApiResponse<Quote | null>>(shipmentPricing(shipmentId)).pipe(map(r => r.data)); }
  history(shipmentId: string): Observable<Quote[]> { return this.http.get<ApiResponse<Quote[]>>(`${shipmentPricing(shipmentId)}/history`).pipe(map(r => r.data)); }
  preview(shipmentId: string): Observable<Quote> { return this.http.post<ApiResponse<Quote>>(`${shipmentPricing(shipmentId)}/preview`, null).pipe(map(r => r.data)); }
  accept(shipmentId: string): Observable<Quote> { return this.http.post<ApiResponse<Quote>>(`${shipmentPricing(shipmentId)}/accept`, null).pipe(map(r => r.data)); }
  reject(shipmentId: string, reason: string | null): Observable<Quote> {
    return this.http.post<ApiResponse<Quote>>(`${shipmentPricing(shipmentId)}/reject`, { reason }).pipe(map(r => r.data));
  }
}

@Injectable({ providedIn: 'root' })
export class AdminPricingApi {
  private readonly http = inject(HttpClient);
  private readonly configs = `${API_BASE}/admin/pricing-configs`;
  private readonly fees = `${API_BASE}/admin/pricing-service-fees`;
  private readonly quotes = `${API_BASE}/admin/quotes`;

  list(filter: { vehicleTypeCode?: string; active?: boolean | null; page: number }): Observable<PageResponse<PricingConfig>> {
    let params = new HttpParams().set('page', filter.page).set('size', 20);
    if (filter.vehicleTypeCode) params = params.set('vehicleTypeCode', filter.vehicleTypeCode);
    if (filter.active !== undefined && filter.active !== null) params = params.set('active', filter.active);
    return this.http.get<ApiResponse<PageResponse<PricingConfig>>>(this.configs, { params }).pipe(map(r => r.data));
  }
  get(id: string): Observable<PricingConfig> { return this.http.get<ApiResponse<PricingConfig>>(`${this.configs}/${enc(id)}`).pipe(map(r => r.data)); }
  create(body: PricingConfigPayload): Observable<PricingConfig> { return this.http.post<ApiResponse<PricingConfig>>(this.configs, body).pipe(map(r => r.data)); }
  update(id: string, body: PricingConfigPayload): Observable<PricingConfig> { return this.http.put<ApiResponse<PricingConfig>>(`${this.configs}/${enc(id)}`, body).pipe(map(r => r.data)); }
  setActive(id: string, active: boolean): Observable<PricingConfig> {
    return this.http.patch<ApiResponse<PricingConfig>>(`${this.configs}/${enc(id)}/${active ? 'activate' : 'deactivate'}`, null).pipe(map(r => r.data));
  }
  history(id: string): Observable<ConfigHistory[]> { return this.http.get<ApiResponse<ConfigHistory[]>>(`${this.configs}/${enc(id)}/history`).pipe(map(r => r.data)); }

  serviceFees(configId: string): Observable<ServiceFee[]> {
    return this.http.get<ApiResponse<ServiceFee[]>>(this.fees, { params: new HttpParams().set('pricingConfigId', configId) }).pipe(map(r => r.data));
  }
  createServiceFee(body: ServiceFeePayload): Observable<ServiceFee> { return this.http.post<ApiResponse<ServiceFee>>(this.fees, body).pipe(map(r => r.data)); }
  updateServiceFee(id: string, body: ServiceFeePayload): Observable<ServiceFee> { return this.http.put<ApiResponse<ServiceFee>>(`${this.fees}/${enc(id)}`, body).pipe(map(r => r.data)); }
  deleteServiceFee(id: string): Observable<void> { return this.http.delete<ApiResponse<null>>(`${this.fees}/${enc(id)}`).pipe(map(() => undefined)); }

  pendingQuotes(page: number): Observable<PageResponse<AdminQuote>> {
    return this.http.get<ApiResponse<PageResponse<AdminQuote>>>(`${this.quotes}/pending`, { params: new HttpParams().set('page', page).set('size', 20) }).pipe(map(r => r.data));
  }
  quote(id: string): Observable<AdminQuote> { return this.http.get<ApiResponse<AdminQuote>>(`${this.quotes}/${enc(id)}`).pipe(map(r => r.data)); }
  adjust(id: string, body: { distanceKm: number | null; adjustmentAmount: number | null; reason: string }): Observable<AdminQuote> {
    return this.http.post<ApiResponse<AdminQuote>>(`${this.quotes}/${enc(id)}/manual-adjustment`, body).pipe(map(r => r.data));
  }
  recalculate(id: string): Observable<AdminQuote> { return this.http.post<ApiResponse<AdminQuote>>(`${this.quotes}/${enc(id)}/recalculate`, null).pipe(map(r => r.data)); }
}

function enc(value: string): string { return encodeURIComponent(value); }
