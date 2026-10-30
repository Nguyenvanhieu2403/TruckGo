import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ApiResponse<T> { success: boolean; message: string; data: T; timestamp: string; path: string; }
export interface HealthData { application: string; status: string; }

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1';
  getHealth(): Observable<ApiResponse<HealthData>> { return this.http.get<ApiResponse<HealthData>>(`${this.baseUrl}/health`); }
}
