import { ShipmentStatus } from '../../core/api/shipment.api';

export const SHIPMENT_STATUS_LABELS: Record<ShipmentStatus, string> = {
  DRAFT: 'Nháp', PUBLISHED: 'Đã đăng', ASSIGNED: 'Đã có tài xế', DRIVER_ARRIVED: 'Tài xế đã đến', PICKED_UP: 'Đã lấy hàng',
  IN_TRANSIT: 'Đang vận chuyển', DELIVERED: 'Đã giao', COMPLETED: 'Hoàn tất', CANCELLED: 'Đã hủy', EXPIRED: 'Hết hạn',
};

export const PRICE_PENDING = 'Chưa tính giá – sẽ được hệ thống báo giá ở bước tiếp theo';

export function formatKg(value: number): string { return `${value.toLocaleString('vi-VN', { maximumFractionDigits: 2 })} kg`; }
export function formatM3(value: number): string { return `${value.toLocaleString('vi-VN', { maximumFractionDigits: 3 })} m³`; }
