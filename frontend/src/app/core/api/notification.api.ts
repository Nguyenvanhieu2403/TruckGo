import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';
import { toParams } from './marketplace.api';

export type NotificationType = 'APPLICATION_RECEIVED' | 'APPLICATION_WITHDRAWN' | 'APPLICATION_ACCEPTED' | 'APPLICATION_REJECTED' | 'APPLICATION_EXPIRED' | 'SHIPMENT_EXPIRED';
export interface AppNotification {
  id: string; type: NotificationType; title: string; content: string; referenceType: 'SHIPMENT' | 'APPLICATION' | null; referenceId: string | null;
  read: boolean; readAt: string | null; createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/notifications`;
  list(page: number, unreadOnly = false): Observable<PageResponse<AppNotification>> {
    return this.http.get<ApiResponse<PageResponse<AppNotification>>>(this.base, { params: toParams({ page, size: 10, unreadOnly }) }).pipe(map(r => r.data));
  }
  unreadCount(): Observable<number> { return this.http.get<ApiResponse<{ count: number }>>(`${this.base}/unread-count`).pipe(map(r => r.data.count)); }
  markRead(id: string): Observable<AppNotification> { return this.http.patch<ApiResponse<AppNotification>>(`${this.base}/${encodeURIComponent(id)}/read`, null).pipe(map(r => r.data)); }
  markAllRead(): Observable<number> { return this.http.patch<ApiResponse<{ updated: number }>>(`${this.base}/read-all`, null).pipe(map(r => r.data.updated)); }
}
