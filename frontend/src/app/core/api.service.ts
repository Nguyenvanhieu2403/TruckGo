import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ApiResponse<T> { success: boolean; message: string; data: T; timestamp: string; path: string; }
export interface HealthData { application: string; status: string; }
export interface PageResponse<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number; }

export const API_BASE = '/api/v1';

/** Field errors returned by the backend as `data: { field: message }` on 400/409. */
export interface ApiError { status: number; message: string; fieldErrors: Record<string, string>; }

export function toApiError(error: unknown): ApiError {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiResponse<unknown>> | null;
    const data = body && typeof body.data === 'object' && body.data !== null ? body.data as Record<string, string> : {};
    const fallback = error.status === 0 ? 'Không thể kết nối tới máy chủ' : error.status === 403 ? 'Bạn không có quyền thực hiện thao tác này' : 'Đã xảy ra lỗi, vui lòng thử lại';
    return { status: error.status, message: body?.message || fallback, fieldErrors: data };
  }
  return { status: 0, message: 'Đã xảy ra lỗi, vui lòng thử lại', fieldErrors: {} };
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = API_BASE;
  getHealth(): Observable<ApiResponse<HealthData>> { return this.http.get<ApiResponse<HealthData>>(`${this.baseUrl}/health`); }
}
