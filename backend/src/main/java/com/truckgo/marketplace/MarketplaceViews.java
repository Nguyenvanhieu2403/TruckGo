package com.truckgo.marketplace;

import com.truckgo.driver.DriverProfile;
import com.truckgo.driver.DriverProfileRepository;
import com.truckgo.marketplace.dto.MarketplaceDtos.Area;
import com.truckgo.marketplace.dto.MarketplaceDtos.DriverBrief;
import com.truckgo.marketplace.dto.MarketplaceDtos.ShipmentBrief;
import com.truckgo.marketplace.dto.MarketplaceDtos.StopView;
import com.truckgo.marketplace.dto.MarketplaceDtos.VehicleBrief;
import com.truckgo.pricing.quote.QuoteStatus;
import com.truckgo.pricing.quote.ShipmentQuote;
import com.truckgo.pricing.quote.ShipmentQuoteRepository;
import com.truckgo.shipment.Shipment;
import com.truckgo.shipment.ShipmentRepository;
import com.truckgo.shipment.ShipmentStatus;
import com.truckgo.shipment.ShipmentStop;
import com.truckgo.shipment.StopType;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.vehicle.Vehicle;
import com.truckgo.vehicle.VehicleRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Batch lookups and the mapping shared by the driver, customer and admin views. */
@Component
class MarketplaceViews {
    private final ShipmentRepository shipments;
    private final VehicleRepository vehicles;
    private final DriverProfileRepository drivers;
    private final UserRepository users;
    private final ShipmentQuoteRepository quotes;

    MarketplaceViews(ShipmentRepository shipments, VehicleRepository vehicles, DriverProfileRepository drivers, UserRepository users, ShipmentQuoteRepository quotes) {
        this.shipments = shipments;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.users = users;
        this.quotes = quotes;
    }

    Map<UUID, Shipment> shipments(Collection<UUID> ids) { return byId(shipments.findAllById(ids), Shipment::getId); }
    Map<UUID, Vehicle> vehicles(Collection<UUID> ids) { return byId(vehicles.findAllById(ids), Vehicle::getId); }
    Map<UUID, DriverProfile> drivers(Collection<UUID> ids) { return byId(drivers.findAllById(ids), DriverProfile::getId); }
    Map<UUID, User> users(Collection<UUID> ids) { return byId(users.findAllById(ids), User::getId); }

    /** Users behind driver profiles, keyed by driver profile id. */
    Map<UUID, User> driverUsers(Collection<DriverProfile> profiles) {
        Map<UUID, User> byUser = users(profiles.stream().map(DriverProfile::getUserId).toList());
        return profiles.stream().filter(p -> byUser.containsKey(p.getUserId())).collect(Collectors.toMap(DriverProfile::getId, p -> byUser.get(p.getUserId())));
    }

    Optional<ShipmentQuote> acceptedQuote(UUID shipmentId) {
        return quotes.findFirstByShipmentIdAndStatusIn(shipmentId, List.of(QuoteStatus.ACCEPTED));
    }

    BigDecimal distanceKm(UUID shipmentId) { return acceptedQuote(shipmentId).map(ShipmentQuote::getDistanceKm).orElse(null); }

    long completedTrips(UUID driverId) { return shipments.countByAssignedDriverIdAndStatus(driverId, ShipmentStatus.COMPLETED); }

    DriverBrief driver(DriverProfile profile, User user, boolean revealPhone) {
        return new DriverBrief(profile.getId(), user == null ? null : user.getFullName(), profile.getLicenseClass(), completedTrips(profile.getId()),
                revealPhone && user != null ? user.getPhone() : null);
    }

    static VehicleBrief vehicle(Vehicle v) {
        return v == null ? null : new VehicleBrief(v.getId(), v.getPlateNumber(), v.getVehicleType(), v.getMaxPayloadKg(), v.getLengthCm(), v.getWidthCm(), v.getHeightCm());
    }

    ShipmentBrief shipment(Shipment s) {
        return new ShipmentBrief(s.getId(), s.getShipmentCode(), s.getStatus(), s.getPickupTime(), pickupArea(s), deliveryArea(s), s.getTotalWeightKg(),
                s.getEstimatedPrice(), distanceKm(s.getId()));
    }

    static Area pickupArea(Shipment s) {
        return s.getStops().stream().filter(x -> x.getStopType() == StopType.PICKUP).findFirst().map(MarketplaceViews::area).orElse(null);
    }

    static Area deliveryArea(Shipment s) {
        return s.getStops().stream().filter(x -> x.getStopType() == StopType.DELIVERY).reduce((a, b) -> b).map(MarketplaceViews::area).orElse(null);
    }

    /** Exact address, contact and stop note only for the driver the order is assigned to. */
    static List<StopView> stops(Shipment s, boolean reveal) {
        return s.getStops().stream().map(x -> new StopView(x.getStopType(), x.getSequenceNo(), x.getProvince(), x.getDistrict(), x.getWard(),
                reveal ? x.getFullAddress() : null, reveal ? x.getContactName() : null, reveal ? x.getContactPhone() : null, reveal ? x.getNote() : null)).toList();
    }

    private static Area area(ShipmentStop stop) { return new Area(stop.getProvince(), stop.getDistrict(), stop.getWard()); }

    private static <T> Map<UUID, T> byId(Iterable<T> rows, Function<T, UUID> id) {
        Map<UUID, T> map = new java.util.HashMap<>();
        rows.forEach(r -> map.put(id.apply(r), r));
        return map;
    }
}
