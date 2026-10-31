import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of, shareReplay } from 'rxjs';
import { API_BASE, ApiResponse } from '../api.service';

export type GeoPrecision = 'EXACT' | 'INTERPOLATED' | 'CENTER' | 'APPROXIMATE';
export interface GeoCandidate { formattedAddress: string; latitude: number; longitude: number; precision: GeoPrecision; partialMatch: boolean; placeId: string | null; }
export interface GeoStatus { provider: string; enabled: boolean; }

export const PRECISION_LABELS: Record<GeoPrecision, string> = {
  EXACT: 'Chính xác', INTERPOLATED: 'Gần đúng theo số nhà', CENTER: 'Trung tâm khu vực', APPROXIMATE: 'Ước lượng (khu vực rộng)',
};

@Injectable({ providedIn: 'root' })
export class GeoApi {
  private readonly http = inject(HttpClient);
  /** Asked once per session; an error means "not available", never "available". */
  private readonly statusOnce = this.http.get<ApiResponse<GeoStatus>>(`${API_BASE}/geo/status`).pipe(
    map(r => r.data), catchError(() => of({ provider: 'none', enabled: false })), shareReplay({ bufferSize: 1, refCount: false }));

  status(): Observable<GeoStatus> { return this.statusOnce; }

  search(query: string): Observable<GeoCandidate[]> {
    return this.http.get<ApiResponse<GeoCandidate[]>>(`${API_BASE}/geo/search`, { params: { q: query, limit: 5 } }).pipe(map(r => r.data));
  }
}
