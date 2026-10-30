import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { API_BASE, ApiResponse, PageResponse } from '../api.service';
import { User, UserRole, UserStatus, VerificationStatus } from '../auth/auth.models';

export interface DriverProfile {
  id: string; userId: string; licenseNumber: string | null; licenseClass: string | null; verificationStatus: VerificationStatus;
  rejectionReason: string | null; submittedAt: string | null; reviewedAt: string | null; createdAt: string; updatedAt: string;
}

export type VehicleType = 'VAN' | 'SMALL_TRUCK' | 'MEDIUM_TRUCK' | 'LARGE_TRUCK' | 'CONTAINER_TRUCK' | 'REFRIGERATED_TRUCK';

export const VEHICLE_TYPE_LABELS: Record<VehicleType, string> = {
  VAN: 'Xe van', SMALL_TRUCK: 'Xe tải nhỏ', MEDIUM_TRUCK: 'Xe tải vừa', LARGE_TRUCK: 'Xe tải lớn',
  CONTAINER_TRUCK: 'Xe container', REFRIGERATED_TRUCK: 'Xe đông lạnh',
};

export interface Vehicle {
  id: string; driverId: string; plateNumber: string; vehicleType: VehicleType; maxPayloadKg: number; lengthCm: number; widthCm: number; heightCm: number;
  registrationExpiryDate: string; verificationStatus: VerificationStatus; rejectionReason: string | null;
  submittedAt: string | null; reviewedAt: string | null; createdAt: string; updatedAt: string;
}

export interface VehiclePayload {
  plateNumber: string; vehicleType: VehicleType; maxPayloadKg: number; lengthCm: number; widthCm: number; heightCm: number; registrationExpiryDate: string;
}

export interface AdminDriver { profile: DriverProfile; fullName: string | null; email: string | null; phone: string | null; }
export interface AdminVehicle { vehicle: Vehicle; driverName: string | null; driverVerificationStatus: VerificationStatus | null; }

@Injectable({ providedIn: 'root' })
export class UserApi {
  private readonly http = inject(HttpClient);
  me(): Observable<User> { return this.http.get<ApiResponse<User>>(`${API_BASE}/users/me`).pipe(map(r => r.data)); }
  update(body: { fullName?: string; email?: string; phone?: string }): Observable<User> {
    return this.http.patch<ApiResponse<User>>(`${API_BASE}/users/me`, body).pipe(map(r => r.data));
  }
  changePassword(body: { currentPassword: string; newPassword: string; confirmNewPassword: string }): Observable<void> {
    return this.http.post<ApiResponse<null>>(`${API_BASE}/users/me/change-password`, body).pipe(map(() => undefined));
  }
}

@Injectable({ providedIn: 'root' })
export class DriverApi {
  private readonly http = inject(HttpClient);
  me(): Observable<DriverProfile> { return this.http.get<ApiResponse<DriverProfile>>(`${API_BASE}/drivers/me`).pipe(map(r => r.data)); }
  update(body: { licenseNumber: string; licenseClass: string }): Observable<DriverProfile> {
    return this.http.patch<ApiResponse<DriverProfile>>(`${API_BASE}/drivers/me`, body).pipe(map(r => r.data));
  }
  submit(): Observable<DriverProfile> { return this.http.post<ApiResponse<DriverProfile>>(`${API_BASE}/drivers/me/submit-verification`, null).pipe(map(r => r.data)); }
}

@Injectable({ providedIn: 'root' })
export class VehicleApi {
  private readonly http = inject(HttpClient);
  mine(): Observable<Vehicle[]> { return this.http.get<ApiResponse<Vehicle[]>>(`${API_BASE}/vehicles/my`).pipe(map(r => r.data)); }
  create(body: VehiclePayload): Observable<Vehicle> { return this.http.post<ApiResponse<Vehicle>>(`${API_BASE}/vehicles`, body).pipe(map(r => r.data)); }
  update(id: string, body: Partial<VehiclePayload>): Observable<Vehicle> {
    return this.http.patch<ApiResponse<Vehicle>>(`${API_BASE}/vehicles/${encodeURIComponent(id)}`, body).pipe(map(r => r.data));
  }
  submit(id: string): Observable<Vehicle> {
    return this.http.post<ApiResponse<Vehicle>>(`${API_BASE}/vehicles/${encodeURIComponent(id)}/submit-verification`, null).pipe(map(r => r.data));
  }
}

@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  pendingDrivers(page: number, size = 20): Observable<PageResponse<AdminDriver>> {
    return this.http.get<ApiResponse<PageResponse<AdminDriver>>>(`${API_BASE}/admin/drivers/pending`, { params: new HttpParams().set('page', page).set('size', size) }).pipe(map(r => r.data));
  }
  decideDriver(id: string, approve: boolean, reason?: string): Observable<AdminDriver> {
    const url = `${API_BASE}/admin/drivers/${encodeURIComponent(id)}/${approve ? 'approve' : 'reject'}`;
    return this.http.post<ApiResponse<AdminDriver>>(url, approve ? null : { reason }).pipe(map(r => r.data));
  }
  pendingVehicles(page: number, size = 20): Observable<PageResponse<AdminVehicle>> {
    return this.http.get<ApiResponse<PageResponse<AdminVehicle>>>(`${API_BASE}/admin/vehicles/pending`, { params: new HttpParams().set('page', page).set('size', size) }).pipe(map(r => r.data));
  }
  decideVehicle(id: string, approve: boolean, reason?: string): Observable<AdminVehicle> {
    const url = `${API_BASE}/admin/vehicles/${encodeURIComponent(id)}/${approve ? 'approve' : 'reject'}`;
    return this.http.post<ApiResponse<AdminVehicle>>(url, approve ? null : { reason }).pipe(map(r => r.data));
  }
  users(filter: { role?: UserRole; status?: UserStatus; page: number }): Observable<PageResponse<User>> {
    let params = new HttpParams().set('page', filter.page).set('size', 20);
    if (filter.role) params = params.set('role', filter.role);
    if (filter.status) params = params.set('status', filter.status);
    return this.http.get<ApiResponse<PageResponse<User>>>(`${API_BASE}/admin/users`, { params }).pipe(map(r => r.data));
  }
}
