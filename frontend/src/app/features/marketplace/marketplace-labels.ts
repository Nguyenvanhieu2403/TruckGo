import { ApplicationStatus } from '../../core/api/marketplace.api';

/** Badge colour per application state (reuses the verification badge palette). */
export function applicationBadge(status: ApplicationStatus): string {
  return status === 'ACCEPTED' ? 'badge badge--approved' : status === 'PENDING' ? 'badge badge--pending_review' : status === 'REJECTED' ? 'badge badge--rejected' : 'badge';
}

/** Messages for the marketplace error codes, phrased for the person who triggered them. */
export const MARKETPLACE_ERROR_HINTS: Record<string, string> = {
  SHIPMENT_ALREADY_ASSIGNED: 'Đơn này vừa có tài xế khác nhận.',
  SHIPMENT_NOT_AVAILABLE: 'Đơn không còn nhận tài xế.',
  APPLICATION_NOT_PENDING: 'Yêu cầu này đã được xử lý trước đó.',
};

export function formatKm(value: number | null): string {
  return value === null ? '—' : `${value.toLocaleString('vi-VN', { maximumFractionDigits: 1 })} km`;
}
