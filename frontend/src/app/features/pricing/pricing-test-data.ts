import { Quote } from '../../core/api/pricing.api';

export const ok = <T>(data: T) => ({ success: true, message: 'ok', data, timestamp: '2026-11-01T00:00:00Z', path: '/' });
export const fail = (message: string, data: unknown = null) => ({ success: false, message, data, timestamp: '2026-11-01T00:00:00Z', path: '/' });

export function quote(overrides: Partial<Quote> = {}): Quote {
  return {
    id: 'q-1', quoteCode: 'TG261101-ABCDEF-Q1', quoteVersion: 1, status: 'CALCULATED', shipmentId: 'sh-1', shipmentCode: 'TG261101-ABCDEF',
    vehicleTypeCode: 'TRUCK_1T', vehicleTypeName: 'Xe tải 1 tấn', vehicleCount: 1, distanceKm: 20, distanceSource: 'PROVIDER', routeDurationMinutes: 35,
    totalWeightKg: 600, totalVolumeM3: 3.24, stopCount: 3,
    breakdown: { basePrice: 100000, distancePrice: 160000, weightSurcharge: 50000, volumeSurcharge: 0, serviceSurcharge: 30000, additionalStopSurcharge: 20000, timeSurcharge: 0, otherSurcharge: 0 },
    totalPrice: 360000, currency: 'VND',
    lines: [{ lineNo: 1, component: 'BASE', code: 'BASE', name: 'Giá mở cửa', description: null, quantity: 1, unitPrice: 100000, amount: 100000 }],
    reviewReasonCode: null, reviewReason: null, rejectionReason: null, adjustmentReason: null, expiresAt: '2026-11-02T00:00:00Z',
    acceptedAt: null, rejectedAt: null, supersededAt: null, createdAt: '2026-11-01T00:00:00Z',
    actions: { acceptable: true, rejectable: true, recalculable: true },
    ...overrides,
  };
}
