import { ServiceDefinition } from '../../core/api/catalog.api';
import { ShipmentDetail } from '../../core/api/shipment.api';

export const CATEGORIES = [{ code: 'ELECTRONICS', name: 'Thiết bị điện tử', description: null }, { code: 'FURNITURE', name: 'Đồ nội thất', description: null }];
export const VEHICLE_TYPES = [
  { code: 'TRUCK_500KG', name: 'Xe tải 500 kg', maxPayloadKg: 500, cargoLengthCm: 200, cargoWidthCm: 130, cargoHeightCm: 130 },
  { code: 'TRUCK_1T', name: 'Xe tải 1 tấn', maxPayloadKg: 1000, cargoLengthCm: 300, cargoWidthCm: 160, cargoHeightCm: 160 },
];
export const BODY_TYPES = [{ code: 'CLOSED_BOX', name: 'Thùng kín' }];
export const SERVICES: ServiceDefinition[] = [
  { code: 'LOADING_SUPPORT', name: 'Hỗ trợ bốc hàng', group: 'LOADING', valueType: 'OPTION', description: null, exclusiveGroup: null, minValue: null, maxValue: null,
    options: [{ value: 'ONE_PERSON', label: 'Cần một người hỗ trợ' }, { value: 'TEAM', label: 'Cần đội bốc xếp' }] },
  { code: 'ELEVATOR_AVAILABLE', name: 'Có thang máy', group: 'SITE', valueType: 'BOOLEAN', description: null, exclusiveGroup: 'ELEVATOR', minValue: null, maxValue: null, options: [] },
  { code: 'ELEVATOR_UNAVAILABLE', name: 'Không có thang máy', group: 'SITE', valueType: 'BOOLEAN', description: null, exclusiveGroup: 'ELEVATOR', minValue: null, maxValue: null, options: [] },
  { code: 'UPSTAIRS_DELIVERY', name: 'Vận chuyển lên tầng', group: 'SITE', valueType: 'NUMBER', description: null, exclusiveGroup: null, minValue: 1, maxValue: 100, options: [] },
  { code: 'FRAGILE_GOODS', name: 'Hàng dễ vỡ', group: 'GOODS', valueType: 'BOOLEAN', description: null, exclusiveGroup: null, minValue: null, maxValue: null, options: [] },
  { code: 'SPECIAL_REQUIREMENT', name: 'Yêu cầu đặc biệt', group: 'OTHER', valueType: 'TEXT', description: null, exclusiveGroup: null, minValue: null, maxValue: null, options: [] },
];

export const ok = <T>(data: T) => ({ success: true, message: 'ok', data, timestamp: '2026-11-01T00:00:00Z', path: '/' });
export const fail = (message: string, data: unknown = null) => ({ success: false, message, data, timestamp: '2026-11-01T00:00:00Z', path: '/' });

export function detail(overrides: Partial<ShipmentDetail> = {}): ShipmentDetail {
  const stop = (type: 'PICKUP' | 'DELIVERY', seq: number, name: string) => ({ id: `s${seq}`, stopType: type, sequenceNo: seq, contactName: name, contactPhone: '+84912345678',
    province: 'Hà Nội', district: 'Cầu Giấy', ward: 'Dịch Vọng', addressDetail: `Số ${seq}`, fullAddress: `Số ${seq}, Dịch Vọng, Cầu Giấy, Hà Nội`, latitude: null, longitude: null, note: null });
  return {
    id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'DRAFT', customerId: 'u-1', pickupTime: '2026-12-01T01:00:00Z', deliveryDeadline: null,
    vehicle: { vehicleTypeCode: 'TRUCK_1T', vehicleTypeName: 'Xe tải 1 tấn', vehicleTypeMaxPayloadKg: 1000, bodyTypeCode: null, bodyTypeName: null, vehicleCount: 1,
      minimumPayloadKg: null, minCargoLengthCm: null, minCargoWidthCm: null, minCargoHeightCm: null },
    totalQuantity: 10, totalWeightKg: 200, totalVolumeM3: 0.6, customerNote: null, priceStatus: 'NOT_QUOTED', estimatedPrice: null, finalPrice: null,
    pickup: null, delivery: null, stops: [stop('PICKUP', 1, 'Kho A'), stop('DELIVERY', 2, 'Cửa hàng B')],
    items: [{ id: 'i1', itemName: 'Thùng', category: 'ELECTRONICS', categoryName: 'Thiết bị điện tử', description: null, quantity: 10, weightPerUnitKg: 20,
      lengthCm: 50, widthCm: 40, heightCm: 30, totalWeightKg: 200, totalVolumeM3: 0.6 }],
    services: [{ serviceCode: 'LOADING_SUPPORT', serviceName: 'Hỗ trợ bốc hàng', group: 'LOADING', valueType: 'OPTION', serviceValue: 'TEAM', valueLabel: 'Cần đội bốc xếp' }],
    attachments: [], statusHistory: [{ oldStatus: null, newStatus: 'DRAFT', changedBy: 'u-1', changeReason: 'Tạo đơn nháp', createdAt: '2026-11-01T00:00:00Z' }],
    warnings: [], actions: { editable: true, publishable: true, cancellable: true, deletable: true, attachmentsEditable: true },
    publishedAt: null, cancelledAt: null, cancellationReason: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', version: 4,
    assignment: null, applications: null,
    ...overrides,
  };
}
