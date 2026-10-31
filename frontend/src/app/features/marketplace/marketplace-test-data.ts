import { Applicant, DriverApplication, MarketplaceListItem, MarketplaceShipmentDetail, StopView } from '../../core/api/marketplace.api';

export { ok, fail } from '../shipments/shipment-test-data';

export function listItem(overrides: Partial<MarketplaceListItem['shipment']> = {}, mine: MarketplaceListItem['myApplicationStatus'] = null): MarketplaceListItem {
  return {
    shipment: {
      id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', pickupTime: '2026-12-01T01:00:00Z', deliveryDeadline: null, applicationDeadline: '2026-12-01T00:00:00Z',
      pickup: { province: 'Hà Nội', district: 'Cầu Giấy', ward: null }, delivery: { province: 'Hải Phòng', district: 'Lê Chân', ward: null }, deliveryStopCount: 1,
      vehicleTypeCode: 'TRUCK_1T', vehicleTypeName: 'Xe tải 1 tấn', bodyTypeCode: null, totalQuantity: 10, totalWeightKg: 600, totalVolumeM3: 3.24,
      price: 360000, distanceKm: 120, publishedAt: '2026-11-01T00:00:00Z', ...overrides,
    },
    myApplicationStatus: mine,
  };
}

const hiddenStop = (type: 'PICKUP' | 'DELIVERY', seq: number, province: string): StopView => ({
  stopType: type, sequenceNo: seq, province, district: 'Quận 1', ward: null, fullAddress: null, contactName: null, contactPhone: null, note: null,
});

export function marketDetail(overrides: Partial<MarketplaceShipmentDetail> = {}): MarketplaceShipmentDetail {
  return {
    id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'PUBLISHED', pickupTime: '2026-12-01T01:00:00Z', deliveryDeadline: null, applicationDeadline: '2026-12-01T00:00:00Z',
    vehicle: { vehicleTypeCode: 'TRUCK_1T', vehicleTypeName: 'Xe tải 1 tấn', vehicleTypeMaxPayloadKg: 1000, bodyTypeCode: null, bodyTypeName: null, vehicleCount: 1,
      minimumPayloadKg: null, minCargoLengthCm: null, minCargoWidthCm: null, minCargoHeightCm: null },
    totalQuantity: 10, totalWeightKg: 600, totalVolumeM3: 3.24, customerNote: null, price: 360000, distanceKm: 120, assignedToMe: false,
    stops: [hiddenStop('PICKUP', 1, 'Hà Nội'), hiddenStop('DELIVERY', 2, 'Hải Phòng')], items: [], services: [],
    driverApproved: true, canApply: true, unavailableReason: null, myApplication: null,
    vehicles: [
      { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500, lengthCm: 420, widthCm: 180, heightCm: 190, eligible: true, reasons: [] },
      { id: 'v-2', plateNumber: '51C-999.99', vehicleType: 'VAN', maxPayloadKg: 400, lengthCm: 200, widthCm: 120, heightCm: 120, eligible: false,
        reasons: ['Tải trọng xe nhỏ hơn tổng khối lượng hàng'] },
    ],
    ...overrides,
  };
}

export function applicant(overrides: Partial<Applicant> = {}): Applicant {
  return {
    id: 'app-1', status: 'PENDING', driver: { driverId: 'd-1', fullName: 'Trần Văn Tài', licenseClass: 'C', completedTrips: 3, phone: null },
    vehicle: { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500, lengthCm: 420, widthCm: 180, heightCm: 190 },
    driverMessage: 'Xe sẵn sàng', responseReason: null, appliedAt: '2026-11-01T02:00:00Z', respondedAt: null, vehicleEligible: true, vehicleIssues: [],
    ...overrides,
  };
}

export function driverApplication(overrides: Partial<DriverApplication> = {}): DriverApplication {
  return {
    id: 'app-1', status: 'PENDING', shipment: { id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'PUBLISHED', pickupTime: '2026-12-01T01:00:00Z',
      pickup: { province: 'Hà Nội', district: null, ward: null }, delivery: { province: 'Hải Phòng', district: null, ward: null }, totalWeightKg: 600, price: 360000, distanceKm: 120 },
    vehicle: { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500, lengthCm: 420, widthCm: 180, heightCm: 190 },
    driverMessage: null, responseReason: null, appliedAt: '2026-11-01T02:00:00Z', respondedAt: null, withdrawable: true,
    stops: [hiddenStop('PICKUP', 1, 'Hà Nội'), hiddenStop('DELIVERY', 2, 'Hải Phòng')],
    ...overrides,
  };
}
