import { NextAction, TrackingState } from '../../core/api/trip.api';

export const NEXT_ACTION_LABELS: Record<NextAction, string> = {
  ARRIVE_PICKUP: 'Đã đến điểm lấy hàng', CONFIRM_PICKUP: 'Xác nhận đã nhận hàng', START_TRIP: 'Bắt đầu vận chuyển',
  ARRIVE_STOP: 'Đã đến điểm dừng', COMPLETE_STOP: 'Hoàn thành điểm dừng', DELIVER: 'Xác nhận đã giao hàng', NONE: '',
};

export const NEXT_ACTION_HINTS: Record<NextAction, string> = {
  ARRIVE_PICKUP: 'Xác nhận khi bạn đã có mặt tại điểm lấy hàng.',
  CONFIRM_PICKUP: 'Kiểm tra số lượng và tình trạng hàng trước khi xác nhận. Có thể đính kèm ảnh.',
  START_TRIP: 'Sau khi bắt đầu, bạn có thể chia sẻ vị trí để khách hàng theo dõi.',
  ARRIVE_STOP: 'Xác nhận khi bạn đã đến điểm dừng tiếp theo.',
  COMPLETE_STOP: 'Xác nhận đã giao/nhận hàng ở điểm dừng này.',
  DELIVER: 'Xác nhận đã giao hàng ở điểm cuối. Khách hàng sẽ nhận mã để xác nhận hoàn thành.',
  NONE: '',
};

export function trackingBadge(state: TrackingState): string {
  return state === 'LIVE' ? 'badge badge--approved' : state === 'STALE' || state === 'PAUSED' ? 'badge badge--pending_review' : state === 'OFFLINE' ? 'badge badge--rejected' : 'badge';
}

export function telLink(phone: string | null): string | null { return phone ? `tel:${phone.replace(/\s+/g, '')}` : null; }

export function directionsLink(address: string | null, lat: number | null, lng: number | null): string | null {
  if (lat !== null && lng !== null) return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
  return address ? `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(address)}` : null;
}
