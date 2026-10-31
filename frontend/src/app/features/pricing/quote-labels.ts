import { PriceComponent, QuoteStatus } from '../../core/api/pricing.api';

export const QUOTE_STATUS_LABELS: Record<QuoteStatus, string> = {
  PENDING: 'Chưa tính giá', CALCULATED: 'Đã báo giá', AWAITING_MANUAL_REVIEW: 'Chờ quản trị viên xử lý', ACCEPTED: 'Đã xác nhận',
  REJECTED: 'Đã từ chối', EXPIRED: 'Hết hạn', SUPERSEDED: 'Đã được thay thế',
};

export const COMPONENT_LABELS: Record<PriceComponent, string> = {
  BASE: 'Giá cơ bản', DISTANCE: 'Phí vận chuyển', WEIGHT: 'Phụ phí khối lượng', VOLUME: 'Phụ phí thể tích', SERVICE: 'Phụ phí dịch vụ',
  STOP: 'Phụ phí điểm dừng', TIME: 'Phụ phí khung giờ', OTHER: 'Các khoản phí khác',
};

export const BREAKDOWN_KEYS: { key: keyof import('../../core/api/pricing.api').QuoteBreakdown; component: PriceComponent }[] = [
  { key: 'basePrice', component: 'BASE' }, { key: 'distancePrice', component: 'DISTANCE' }, { key: 'weightSurcharge', component: 'WEIGHT' },
  { key: 'volumeSurcharge', component: 'VOLUME' }, { key: 'serviceSurcharge', component: 'SERVICE' }, { key: 'additionalStopSurcharge', component: 'STOP' },
  { key: 'timeSurcharge', component: 'TIME' }, { key: 'otherSurcharge', component: 'OTHER' },
];
