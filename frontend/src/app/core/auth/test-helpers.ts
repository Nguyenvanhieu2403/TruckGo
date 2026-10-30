import { AuthTokenResponse, User, UserRole } from './auth.models';

export function testUser(role: UserRole = 'CUSTOMER'): User {
  return { id: 'u-1', fullName: 'Nguyen Van A', email: 'a@example.com', phone: '+84912345678', role, status: 'ACTIVE',
    emailVerified: false, phoneVerified: false, createdAt: '2026-10-30T00:00:00Z', lastLoginAt: null };
}

export function tokenEnvelope(token: string, role: UserRole = 'CUSTOMER'): { success: boolean; message: string; data: AuthTokenResponse; timestamp: string; path: string } {
  return { success: true, message: 'ok', data: { accessToken: token, tokenType: 'Bearer', expiresIn: 900, user: testUser(role) }, timestamp: '2026-10-30T00:00:00Z', path: '/api/v1/auth/login' };
}

export function envelope<T>(data: T, message = 'ok') { return { success: true, message, data, timestamp: '2026-10-30T00:00:00Z', path: '/' }; }

export function failure(message: string, data: unknown = null) { return { success: false, message, data, timestamp: '2026-10-30T00:00:00Z', path: '/' }; }
