import { ApplicationStatus } from '../../core/api/marketplace.api';

/** Badge colour per application state (reuses the verification badge palette). */
export function applicationBadge(status: ApplicationStatus): string {
  return status === 'ACCEPTED' ? 'badge badge--approved' : status === 'PENDING' ? 'badge badge--pending_review' : status === 'REJECTED' || status === 'RELEASED' ? 'badge badge--rejected' : 'badge';
}

/** Messages for the marketplace error codes, phrased for the person who triggered them. */
export const MARKETPLACE_ERROR_HINTS: Record<string, string> = {
  SHIPMENT_ALREADY_ASSIGNED: 'Đơn này vừa có tài xế khác nhận.',
  SHIPMENT_NOT_AVAILABLE: 'Đơn không còn nhận tài xế.',
  APPLICATION_NOT_PENDING: 'Yêu cầu này đã được xử lý trước đó.',
  SCHEDULE_CONFLICT: 'Tài xế hoặc xe đã có chuyến khác trùng thời gian.',
  DRIVER_ALREADY_ON_SHIPMENT: 'Tài xế này đã được phân công cho đơn.',
  VEHICLE_ALREADY_ON_SHIPMENT: 'Xe này đã được phân công cho đơn.',
  TRIP_ALREADY_STARTED: 'Chuyến đã bắt đầu, không thể gỡ hoặc thay tài xế. Hãy báo sự cố.',
  ASSIGNMENT_NOT_ACTIVE: 'Phân công này đã kết thúc.',
};

/** "Cần 3 xe · đã có 1" for multi-vehicle orders, empty for single-vehicle ones. */
export function vehicleProgress(vehicleCount: number, assigned: number): string {
  return vehicleCount > 1 ? `Cần ${vehicleCount} xe · đã có ${assigned}` : '';
}

export function formatKm(value: number | null): string {
  return value === null ? '—' : `${value.toLocaleString('vi-VN', { maximumFractionDigits: 1 })} km`;
}

/** Names of the vehicle_body_types catalog codes (seeded in V3); unknown codes are shown as is. */
const BODY_TYPE_NAMES: Record<string, string> = {
  ANY: 'Không yêu cầu', OPEN: 'Thùng lửng', TARPAULIN: 'Thùng mui bạt', CLOSED_BOX: 'Thùng kín', REFRIGERATED: 'Thùng đông lạnh',
  CONTAINER: 'Container', FLATBED: 'Sàn phẳng / chở máy móc',
};

export function bodyTypeLabel(code: string | null | undefined): string {
  return code ? BODY_TYPE_NAMES[code] ?? code : 'Chưa khai báo loại thùng';
}
