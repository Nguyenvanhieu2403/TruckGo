import { CustomerTrip, DriverTrip, StopProgress, Tracking } from '../../core/api/trip.api';

export { ok, fail } from '../shipments/shipment-test-data';

export const stop = (seq: number, type: 'PICKUP' | 'DELIVERY', status: StopProgress['status'] = 'PENDING', current = false): StopProgress => ({
  stopId: `st-${seq}`, stopType: type, sequenceNo: seq, province: 'Hà Nội', district: 'Cầu Giấy', ward: null, fullAddress: `Số ${seq} Trần Thái Tông, Hà Nội`,
  contactName: `Liên hệ ${seq}`, contactPhone: '0912345678', stopNote: null, latitude: null, longitude: null, status, arrivedAt: null, departedAt: null, progressNote: null, current,
});

export function driverTrip(overrides: Partial<DriverTrip> = {}): DriverTrip {
  return {
    id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'ASSIGNED', pickupTime: '2026-11-02T01:00:00Z', deliveryDeadline: null, customerName: 'Khách A', customerPhone: '0987654321',
    requirement: { vehicleTypeCode: 'TRUCK_1T', vehicleTypeName: 'Xe tải 1 tấn', vehicleTypeMaxPayloadKg: 1000, bodyTypeCode: null, bodyTypeName: null, vehicleCount: 1,
      minimumPayloadKg: null, minCargoLengthCm: null, minCargoWidthCm: null, minCargoHeightCm: null },
    vehicle: { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500 }, totalQuantity: 10, totalWeightKg: 600, totalVolumeM3: 3.24,
    customerNote: null, stops: [stop(1, 'PICKUP', 'PENDING', true), stop(2, 'DELIVERY')], items: [], services: [], quote: null,
    milestones: { assignedAt: '2026-11-01T00:00:00Z', driverArrivedAt: null, pickedUpAt: null, inTransitAt: null, deliveredAt: null, completedAt: null },
    statusHistory: [], photos: [], nextAction: 'ARRIVE_PICKUP', nextStopId: null, trackingAllowed: false, trackingSession: null,
    trackingPolicy: { movingIntervalSeconds: 8, idleIntervalSeconds: 30, minDistanceMeters: 25 }, deliveryPhotoRequired: false, awaitingCustomerConfirmation: false, openIncidents: 0,
    ...overrides,
  };
}

export const tracking = (overrides: Partial<Tracking> = {}): Tracking => ({
  state: 'LIVE', session: 'ACTIVE', latitude: 21.0285, longitude: 105.8542, accuracy: 8, speed: 35, heading: 90, recordedAt: '2026-11-01T03:00:00Z', ageSeconds: 5, ...overrides,
});

export function customerTrip(overrides: Partial<CustomerTrip> = {}): CustomerTrip {
  return {
    id: 'sh-1', shipmentCode: 'TG261101-ABCDEF', status: 'IN_TRANSIT', driver: { driverId: 'd-1', fullName: 'Tài Xế An', phone: '0912000111', licenseClass: 'C' },
    vehicle: { id: 'v-1', plateNumber: '51C-123.45', vehicleType: 'SMALL_TRUCK', maxPayloadKg: 1500 }, stops: [stop(1, 'PICKUP', 'COMPLETED'), stop(2, 'DELIVERY', 'PENDING', true)],
    milestones: { assignedAt: null, driverArrivedAt: null, pickedUpAt: null, inTransitAt: null, deliveredAt: null, completedAt: null }, statusHistory: [], photos: [],
    tracking: tracking(), estimatedArrival: null, confirmation: null, openIncidents: 0, ...overrides,
  };
}
