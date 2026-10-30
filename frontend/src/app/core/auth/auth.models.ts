export type UserRole = 'CUSTOMER' | 'DRIVER' | 'ADMIN';
export type UserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'LOCKED';
export type VerificationStatus = 'NOT_SUBMITTED' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';

export interface User {
  id: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  role: UserRole;
  status: UserStatus;
  emailVerified: boolean;
  phoneVerified: boolean;
  createdAt: string;
  lastLoginAt: string | null;
}

export interface AuthTokenResponse { accessToken: string; tokenType: 'Bearer'; expiresIn: number; user: User; }
export interface RegistrationResponse { user: User; driverVerificationStatus: VerificationStatus | null; }

export interface RegisterCustomerPayload { fullName: string; email?: string; phone?: string; password: string; confirmPassword: string; }
export interface RegisterDriverPayload extends RegisterCustomerPayload { licenseNumber: string; licenseClass: string; }

export const HOME_BY_ROLE: Record<UserRole, string> = { CUSTOMER: '/customer', DRIVER: '/driver/verification', ADMIN: '/admin/drivers' };
