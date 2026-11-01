import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, shareReplay } from 'rxjs';
import { API_BASE, ApiResponse } from '../api.service';

export interface ItemCategory { code: string; name: string; description: string | null; }
export interface CatalogVehicleType { code: string; name: string; maxPayloadKg: number; cargoLengthCm: number | null; cargoWidthCm: number | null; cargoHeightCm: number | null; }
export interface BodyType { code: string; name: string; }
export type ServiceValueType = 'BOOLEAN' | 'OPTION' | 'NUMBER' | 'TEXT';
export type ServiceGroup = 'LOADING' | 'SITE' | 'GOODS' | 'OTHER';
export interface ServiceDefinition {
  code: string; name: string; group: ServiceGroup; valueType: ServiceValueType; description: string | null;
  exclusiveGroup: string | null; minValue: number | null; maxValue: number | null; options: { value: string; label: string }[];
  /** ORDER (whole order), STOP (per stop) or BOTH; stopTypes says which stops (PICKUP, DELIVERY, ANY). */
  appliesTo?: 'ORDER' | 'STOP' | 'BOTH'; stopTypes?: 'PICKUP' | 'DELIVERY' | 'ANY' | null;
}

/** Whether a service can be ordered at a stop of this type. */
export function allowedAtStop(def: ServiceDefinition, stopType: 'PICKUP' | 'DELIVERY'): boolean {
  return (def.appliesTo === 'STOP' || def.appliesTo === 'BOTH') && (def.stopTypes === 'ANY' || def.stopTypes === stopType);
}

/**
 * Catalogs always come from the backend (the same data it validates against). Each list is fetched once per
 * session and shared; a failed request is not cached.
 */
@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);
  private readonly cache = new Map<string, Observable<unknown>>();

  itemCategories(): Observable<ItemCategory[]> { return this.load<ItemCategory[]>('item-categories'); }
  vehicleTypes(): Observable<CatalogVehicleType[]> { return this.load<CatalogVehicleType[]>('vehicle-types'); }
  bodyTypes(): Observable<BodyType[]> { return this.load<BodyType[]>('vehicle-body-types'); }
  services(): Observable<ServiceDefinition[]> { return this.load<ServiceDefinition[]>('shipment-services'); }

  private load<T>(name: string): Observable<T> {
    let cached = this.cache.get(name) as Observable<T> | undefined;
    if (!cached) {
      cached = this.http.get<ApiResponse<T>>(`${API_BASE}/catalogs/${name}`).pipe(map(r => r.data), shareReplay({ bufferSize: 1, refCount: false }));
      this.cache.set(name, cached);
      cached.subscribe({ error: () => this.cache.delete(name) });
    }
    return cached;
  }
}
